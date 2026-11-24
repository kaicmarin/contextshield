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
 * A revisão confirmada fica registrada para um treino futuro.
 * Ela não publica modelo e o campo de rótulo permanece falso.
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
            "CLOSED", Set.of()
    );

    private final JdbcTemplate jdbc;

    public CaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> open(long organizationId, String sourceType, String sourceId, String actor) {
        if (sourceType == null || sourceType.isBlank() || sourceId == null || sourceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe a origem do caso.");
        }
        String id = "CASE-" + UUID.randomUUID().toString().substring(0, 8);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbc.update("""
                insert into review_case
                (id, organization_id, source_type, source_id, state, actor, training_label, created_at, updated_at)
                values (?, ?, ?, ?, 'OPEN', ?, false, ?, ?)
                """, id, organizationId, sourceType.trim(), sourceId.trim(), actor, now, now);
        event(id, organizationId, actor, "NONE", "OPEN", null, now);
        return get(organizationId, id);
    }

    public Map<String, Object> move(long organizationId, String id, String to, String note, String actor) {
        Map<String, Object> current = get(organizationId, id);
        String from = String.valueOf(current.get("state"));
        if (!NEXT.getOrDefault(from, Set.of()).contains(to)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transição não permitida.");
        }
        if (to.startsWith("CONFIRMED") && (note == null || note.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A confirmação precisa de uma nota.");
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbc.update("""
                update review_case
                set state = ?, note = ?, actor = ?, training_label = false, updated_at = ?
                where id = ? and organization_id = ?
                """, to, note == null ? null : note.trim(), actor, now, id, organizationId);
        event(id, organizationId, actor, from, to, note, now);
        return get(organizationId, id);
    }

    public List<Map<String, Object>> list(long organizationId) {
        return jdbc.query("""
                select id, source_type, source_id, state, note, actor, training_label, updated_at
                from review_case where organization_id = ? order by updated_at desc
                """, (rs, n) -> row(rs), organizationId);
    }

    private Map<String, Object> get(long organizationId, String id) {
        List<Map<String, Object>> rows = jdbc.query("""
                select id, source_type, source_id, state, note, actor, training_label, updated_at
                from review_case where id = ? and organization_id = ?
                """, (rs, n) -> row(rs), id, organizationId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Caso não encontrado.");
        return rows.get(0);
    }

    private void event(String id, long organizationId, String actor, String from, String to, String note, LocalDateTime at) {
        jdbc.update("""
                insert into review_event (case_id, organization_id, actor, from_state, to_state, note, at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, id, organizationId, actor == null ? "serviço" : actor, from, to, note, at);
    }

    private static Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", rs.getString("id"));
        out.put("sourceType", rs.getString("source_type"));
        out.put("sourceId", rs.getString("source_id"));
        out.put("state", rs.getString("state"));
        out.put("note", rs.getString("note"));
        out.put("actor", rs.getString("actor"));
        out.put("trainingLabel", false);
        out.put("publishesModel", false);
        return out;
    }
}
