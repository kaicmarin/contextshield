package com.contextshield.threat;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Uma denúncia entra como UNVERIFIED. Confirmar exige revisão e não vira rótulo de treino. */
@Service
public class IndicatorService {
    static final Set<String> STATUSES = Set.of("UNVERIFIED", "IN_REVIEW", "CONFIRMED", "DISCARDED");
    private static final Map<String, Set<String>> NEXT = Map.of(
            "UNVERIFIED", Set.of("IN_REVIEW", "DISCARDED"),
            "IN_REVIEW", Set.of("CONFIRMED", "DISCARDED"),
            "CONFIRMED", Set.of(),
            "DISCARDED", Set.of()
    );
    private final JdbcTemplate jdbc;

    public IndicatorService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> open(long organizationId, String actor, String kind, String value) {
        if (kind == null || kind.isBlank() || value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o tipo e o valor.");
        }
        if (!Set.of("url", "phone", "message", "seller", "domain", "account").contains(kind.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de indicador não reconhecido.");
        }
        if (value.length() > 2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valor longo demais.");
        String id = "IND-" + UUID.randomUUID().toString().substring(0, 8);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbc.update("""
                insert into threat_indicator
                (id, organization_id, kind, value_hash, display, status, visibility, training_label, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'UNVERIFIED', 'PRIVATE', false, ?, ?)
                """, id, organizationId, kind.trim(), hash(value), display(value), now, now);
        event(id, organizationId, actor, "NONE", "UNVERIFIED", "indicador aberto", now);
        return get(organizationId, id);
    }

    public Map<String, Object> review(long organizationId, String actor, String id, String status, String note, String visibility) {
        if (!STATUSES.contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Situação inválida.");
        if (("CONFIRMED".equals(status) || "DISCARDED".equals(status)) && (note == null || note.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A revisão precisa de uma nota.");
        }
        Map<String, Object> current = get(organizationId, id);
        String from = String.valueOf(current.get("status"));
        if (!NEXT.getOrDefault(from, Set.of()).contains(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A confirmação só sai da revisão.");
        }
        String scope = "CONFIRMED".equals(status) && "SHARED".equals(visibility) ? "SHARED" : "PRIVATE";
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbc.update("""
                update threat_indicator
                set status = ?, visibility = ?, note = ?, training_label = false, updated_at = ?
                where id = ? and organization_id = ?
                """, status, scope, note == null ? null : note.trim(), now, id, organizationId);
        event(id, organizationId, actor, from, status, note, now);
        return get(organizationId, id);
    }

    public List<Map<String, Object>> list(long organizationId) {
        return jdbc.query("""
                select i.id, i.kind, i.value_hash, i.display, i.status, i.visibility, i.note,
                       (select r.actor from threat_review r where r.indicator_id = i.id order by r.id desc limit 1) reviewer,
                       (select count(*) from threat_indicator t2 where t2.value_hash = i.value_hash and t2.status = 'CONFIRMED'
                            and (t2.organization_id = ? or t2.visibility = 'SHARED')) reputation
                from threat_indicator i where i.organization_id = ? order by i.updated_at desc
                """, (rs, n) -> row(rs, true), organizationId, organizationId);
    }

    public List<Map<String, Object>> history(long organizationId, String id) {
        get(organizationId, id);
        return jdbc.query("""
                select actor, from_status, to_status, note, at
                from threat_review where indicator_id = ? and organization_id = ? order by id
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("actor", rs.getString("actor"));
            row.put("from", rs.getString("from_status"));
            row.put("to", rs.getString("to_status"));
            row.put("note", rs.getString("note"));
            row.put("at", rs.getTimestamp("at").toLocalDateTime());
            return row;
        }, id, organizationId);
    }

    /** Visão entre organizações: só confirmado e compartilhado, sem texto e sem hash. */
    public List<Map<String, Object>> shared(long organizationId) {
        return jdbc.query("""
                select i.id, i.kind, i.value_hash, i.display, i.status, i.visibility, i.note,
                       (select r.actor from threat_review r where r.indicator_id = i.id order by r.id desc limit 1) reviewer,
                       (select count(*) from threat_indicator t2 where t2.value_hash = i.value_hash and t2.status = 'CONFIRMED'
                            and t2.visibility = 'SHARED') reputation
                from threat_indicator i where i.visibility = 'SHARED' and i.status = 'CONFIRMED' order by i.updated_at desc
                """, (rs, n) -> row(rs, false));
    }

    /**
     * A consulta casa o valor aqui dentro.
     * Outra organização só fica sabendo de indicador confirmado e compartilhado.
     */
    public Map<String, Object> match(long organizationId, String kind, String value, boolean sharedOnly) {
        if (kind == null || value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o tipo e o valor.");
        }
        String digest = hash(value);
        List<Map<String, Object>> rows = jdbc.query("""
                select status, visibility, organization_id from threat_indicator
                where kind = ? and value_hash = ? and status <> 'DISCARDED'
                """, (rs, n) -> Map.of(
                "status", rs.getString("status"),
                "visibility", rs.getString("visibility"),
                "organizationId", rs.getLong("organization_id")), kind.trim(), digest);
        String status = null;
        for (Map<String, Object> row : rows) {
            boolean own = ((Number) row.get("organizationId")).longValue() == organizationId;
            boolean shared = "SHARED".equals(row.get("visibility")) && "CONFIRMED".equals(row.get("status"));
            if (sharedOnly && !shared) continue;
            if (!sharedOnly && !own && !shared) continue;
            status = prefer(status, String.valueOf(row.get("status")));
        }
        int reputation = reputation(digest, organizationId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("matched", status != null);
        out.put("status", status);
        out.put("reputation", status == null ? 0 : reputation);
        out.put("trainingLabel", false);
        out.put("fetchesUrl", false);
        return out;
    }

    private Map<String, Object> get(long organizationId, String id) {
        List<Map<String, Object>> rows = jdbc.query("""
                select i.id, i.kind, i.value_hash, i.display, i.status, i.visibility, i.note,
                       (select r.actor from threat_review r where r.indicator_id = i.id order by r.id desc limit 1) reviewer,
                       (select count(*) from threat_indicator t2 where t2.value_hash = i.value_hash and t2.status = 'CONFIRMED'
                            and (t2.organization_id = ? or t2.visibility = 'SHARED')) reputation
                from threat_indicator i where i.id = ? and i.organization_id = ?
                """, (rs, n) -> row(rs, true), organizationId, id, organizationId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Indicador não encontrado.");
        return rows.get(0);
    }

    private static Map<String, Object> row(java.sql.ResultSet rs, boolean owner) throws java.sql.SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", rs.getString("id"));
        out.put("kind", rs.getString("kind"));
        if (owner) out.put("display", rs.getString("display"));
        out.put("status", rs.getString("status"));
        out.put("visibility", rs.getString("visibility"));
        if (owner) out.put("note", rs.getString("note"));
        out.put("reputation", rs.getInt("reputation"));
        out.put("reviewedBy", rs.getString("reviewer"));
        out.put("trainingLabel", false);
        out.put("fetchesUrl", false);
        return out;
    }

    private int reputation(String digest, long organizationId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from threat_indicator
                where value_hash = ? and status = 'CONFIRMED'
                  and (organization_id = ? or visibility = 'SHARED')
                """, Integer.class, digest, organizationId);
        return count == null ? 0 : count;
    }

    private void event(String id, long organizationId, String actor, String from, String to, String note, LocalDateTime at) {
        jdbc.update("""
                insert into threat_review (indicator_id, organization_id, actor, from_status, to_status, note, at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, id, organizationId, actor, from, to, note, Timestamp(at));
    }

    private static java.sql.Timestamp Timestamp(LocalDateTime at) {
        return java.sql.Timestamp.valueOf(at);
    }

    private static String prefer(String current, String candidate) {
        if ("CONFIRMED".equals(current) || "CONFIRMED".equals(candidate)) return "CONFIRMED";
        if ("IN_REVIEW".equals(current) || "IN_REVIEW".equals(candidate)) return "IN_REVIEW";
        return candidate;
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String display(String value) {
        String trimmed = value.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80);
    }
}
