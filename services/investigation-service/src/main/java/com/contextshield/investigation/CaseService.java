package com.contextshield.investigation;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A revisão confirmada fica registrada.
 * Ela não publica modelo e o campo de rótulo permanece falso.
 * O responsável é o sujeito do token, nunca um nome enviado no corpo.
 */
@Service
public class CaseService {
    private static final Map<String, Set<String>> NEXT = Map.of(
            "OPEN", Set.of("UNDER_REVIEW", "WAITING_EVIDENCE", "CLOSED"),
            "UNDER_REVIEW", Set.of("WAITING_EVIDENCE", "CONFIRMED_FRAUD", "CONFIRMED_LEGIT", "INCONCLUSIVE", "CLOSED"),
            "WAITING_EVIDENCE", Set.of("UNDER_REVIEW", "INCONCLUSIVE", "CLOSED"),
            "CONFIRMED_FRAUD", Set.of("CLOSED"),
            "CONFIRMED_LEGIT", Set.of("CLOSED"),
            "INCONCLUSIVE", Set.of("UNDER_REVIEW", "CLOSED"),
            "CLOSED", Set.of("OPEN")
    );
    private static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH");
    private static final Set<String> EVIDENCE = Set.of("NOTE", "TRANSACTION", "DOCUMENT");

    private final JdbcTemplate jdbc;

    public CaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> open(long organizationId, String actor, String sourceType, String sourceId, String txId, String priority) {
        if (sourceType == null || sourceType.isBlank() || sourceId == null || sourceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe a origem do caso.");
        }
        String level = priority == null || priority.isBlank() ? "NORMAL" : priority;
        if (!PRIORITIES.contains(level)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Prioridade inválida.");
        String id = "CASE-" + UUID.randomUUID().toString().substring(0, 8);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbc.update("""
                insert into review_case
                (id, organization_id, source_type, source_id, tx_id, state, priority, actor, training_label, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'OPEN', ?, ?, false, ?, ?)
                """, id, organizationId, sourceType.trim(), sourceId.trim(), blank(txId), level, actor, now, now);
        event(id, organizationId, actor, "NONE", "OPEN", null, now);
        return dossier(organizationId, id);
    }

    public Map<String, Object> assign(long organizationId, String id, String actor) {
        Map<String, Object> current = caseRow(organizationId, id);
        String from = String.valueOf(current.get("state"));
        if (!Set.of("OPEN", "WAITING_EVIDENCE", "INCONCLUSIVE").contains(from)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este caso não pode ser assumido agora.");
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbc.update("""
                update review_case set state = 'UNDER_REVIEW', assignee = ?, actor = ?, updated_at = ?
                where id = ? and organization_id = ?
                """, actor, actor, now, id, organizationId);
        event(id, organizationId, actor, from, "UNDER_REVIEW", "assumido", now);
        return dossier(organizationId, id);
    }

    public Map<String, Object> move(long organizationId, String id, String to, String note, String actor) {
        Map<String, Object> current = caseRow(organizationId, id);
        String from = String.valueOf(current.get("state"));
        if (!NEXT.getOrDefault(from, Set.of()).contains(to)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transição não permitida.");
        }
        if ((to.startsWith("CONFIRMED") || "OPEN".equals(to) || "CLOSED".equals(to)) && (note == null || note.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Esta transição precisa de uma nota.");
        }
        if ("OPEN".equals(to) && !"CLOSED".equals(from)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Só um caso encerrado reabre.");
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbc.update("""
                update review_case
                set state = ?, note = ?, actor = ?, assignee = case when ? = 'OPEN' then null else assignee end,
                    training_label = false, updated_at = ?
                where id = ? and organization_id = ?
                """, to, note == null ? null : note.trim(), actor, to, now, id, organizationId);
        event(id, organizationId, actor, from, to, note, now);
        return dossier(organizationId, id);
    }

    public Map<String, Object> evidence(long organizationId, String id, String actor, String kind, String body) {
        caseRow(organizationId, id);
        if (kind == null || !EVIDENCE.contains(kind) || body == null || body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o tipo e o texto da evidência.");
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbc.update("""
                insert into review_evidence (case_id, organization_id, kind, body, actor, at)
                values (?, ?, ?, ?, ?, ?)
                """, id, organizationId, kind, body.trim(), actor, java.sql.Timestamp.valueOf(now));
        event(id, organizationId, actor, "EVIDENCE", "EVIDENCE", kind, now);
        return dossier(organizationId, id);
    }

    public Map<String, Object> comment(long organizationId, String id, String actor, String body) {
        caseRow(organizationId, id);
        if (body == null || body.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O comentário está vazio.");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbc.update("""
                insert into review_comment (case_id, organization_id, body, actor, at) values (?, ?, ?, ?, ?)
                """, id, organizationId, body.trim(), actor, java.sql.Timestamp.valueOf(now));
        return dossier(organizationId, id);
    }

    public List<Map<String, Object>> list(long organizationId, String state) {
        if (state == null || state.isBlank()) {
            return jdbc.query("""
                    select id, source_type, source_id, tx_id, state, priority, note, actor, assignee, training_label, updated_at
                    from review_case where organization_id = ? order by updated_at desc
                    """, (rs, n) -> row(rs), organizationId);
        }
        return jdbc.query("""
                select id, source_type, source_id, tx_id, state, priority, note, actor, assignee, training_label, updated_at
                from review_case where organization_id = ? and state = ? order by updated_at desc
                """, (rs, n) -> row(rs), organizationId, state);
    }

    public Map<String, Object> dossier(long organizationId, String id) {
        Map<String, Object> row = caseRow(organizationId, id);
        row.put("events", events(organizationId, id));
        row.put("evidence", evidenceOf(organizationId, id));
        row.put("comments", comments(organizationId, id));
        return row;
    }

    public Map<String, Object> report(long organizationId) {
        List<Map<String, Object>> byState = jdbc.query("""
                select state, count(*) total from review_case where organization_id = ? group by state order by state
                """, (rs, n) -> Map.of("state", rs.getString("state"), "total", rs.getInt("total")), organizationId);
        Integer labels = jdbc.queryForObject("""
                select count(*) from review_case
                where organization_id = ? and state in ('CONFIRMED_FRAUD', 'CONFIRMED_LEGIT')
                """, Integer.class, organizationId);
        return Map.of("byState", byState, "confirmed", labels == null ? 0 : labels,
                "trainingLabel", false, "publishesModel", false);
    }

    private Map<String, Object> caseRow(long organizationId, String id) {
        List<Map<String, Object>> rows = jdbc.query("""
                select id, source_type, source_id, tx_id, state, priority, note, actor, assignee, training_label, updated_at
                from review_case where id = ? and organization_id = ?
                """, (rs, n) -> row(rs), id, organizationId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Caso não encontrado.");
        return rows.get(0);
    }

    private List<Map<String, Object>> events(long organizationId, String id) {
        return jdbc.query("""
                select actor, from_state, to_state, note, at from review_event
                where case_id = ? and organization_id = ? order by id
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("actor", rs.getString("actor"));
            row.put("from", rs.getString("from_state"));
            row.put("to", rs.getString("to_state"));
            row.put("note", rs.getString("note"));
            row.put("at", rs.getTimestamp("at").toLocalDateTime());
            return row;
        }, id, organizationId);
    }

    private List<Map<String, Object>> evidenceOf(long organizationId, String id) {
        return jdbc.query("""
                select kind, body, actor, at from review_evidence
                where case_id = ? and organization_id = ? order by id
                """, (rs, n) -> Map.of("kind", rs.getString("kind"), "body", rs.getString("body"),
                "actor", rs.getString("actor"), "at", rs.getTimestamp("at").toLocalDateTime()), id, organizationId);
    }

    private List<Map<String, Object>> comments(long organizationId, String id) {
        return jdbc.query("""
                select body, actor, at from review_comment
                where case_id = ? and organization_id = ? order by id
                """, (rs, n) -> Map.of("body", rs.getString("body"), "actor", rs.getString("actor"),
                "at", rs.getTimestamp("at").toLocalDateTime()), id, organizationId);
    }

    private void event(String id, long organizationId, String actor, String from, String to, String note, LocalDateTime at) {
        jdbc.update("""
                insert into review_event (case_id, organization_id, actor, from_state, to_state, note, at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, id, organizationId, actor, from, to, note, java.sql.Timestamp.valueOf(at));
    }

    private static Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", rs.getString("id"));
        out.put("sourceType", rs.getString("source_type"));
        out.put("sourceId", rs.getString("source_id"));
        out.put("txId", rs.getString("tx_id"));
        out.put("state", rs.getString("state"));
        out.put("priority", rs.getString("priority"));
        out.put("note", rs.getString("note"));
        out.put("actor", rs.getString("actor"));
        out.put("assignee", rs.getString("assignee"));
        out.put("trainingLabel", false);
        out.put("publishesModel", false);
        return out;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
