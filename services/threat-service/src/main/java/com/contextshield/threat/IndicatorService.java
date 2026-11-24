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

/** Uma denúncia entra como UNVERIFIED. Confirmar exige ação explícita e não vira rótulo de treino. */
@Service
public class IndicatorService {
    static final Set<String> STATUSES = Set.of("UNVERIFIED", "IN_REVIEW", "CONFIRMED", "DISCARDED");
    private final JdbcTemplate jdbc;

    public IndicatorService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> open(long organizationId, String kind, String value) {
        if (kind == null || kind.isBlank() || value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o tipo e o valor.");
        }
        if (value.length() > 2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valor longo demais.");
        String id = "IND-" + UUID.randomUUID().toString().substring(0, 8);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbc.update("""
                insert into threat_indicator
                (id, organization_id, kind, value_hash, display, status, visibility, training_label, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'UNVERIFIED', 'PRIVATE', false, ?, ?)
                """, id, organizationId, kind.trim(), hash(value), display(value), now, now);
        return get(organizationId, id);
    }

    public Map<String, Object> review(long organizationId, String id, String status, String note, String visibility) {
        if (!STATUSES.contains(status) || "UNVERIFIED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Situação inválida.");
        }
        if (("CONFIRMED".equals(status) || "DISCARDED".equals(status)) && (note == null || note.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A revisão precisa de uma nota.");
        }
        Map<String, Object> current = get(organizationId, id);
        if ("DISCARDED".equals(current.get("status")) || "CONFIRMED".equals(current.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "O indicador já foi concluído.");
        }
        String scope = "CONFIRMED".equals(status) && "SHARED".equals(visibility) ? "SHARED" : "PRIVATE";
        jdbc.update("""
                update threat_indicator
                set status = ?, visibility = ?, note = ?, training_label = false, updated_at = ?
                where id = ? and organization_id = ?
                """, status, scope, note == null ? null : note.trim(), LocalDateTime.now().withNano(0), id, organizationId);
        return get(organizationId, id);
    }

    public List<Map<String, Object>> list(long organizationId) {
        return jdbc.query("""
                select id, kind, value_hash, display, status, visibility, training_label, note, updated_at
                from threat_indicator where organization_id = ? order by updated_at desc
                """, (rs, n) -> row(rs, true), organizationId);
    }

    /** Visão entre organizações: só confirmado, sem o texto original. */
    public List<Map<String, Object>> shared() {
        return jdbc.query("""
                select id, kind, value_hash, display, status, visibility, training_label, note, updated_at
                from threat_indicator where visibility = 'SHARED' and status = 'CONFIRMED' order by updated_at desc
                """, (rs, n) -> row(rs, false));
    }

    private Map<String, Object> get(long organizationId, String id) {
        List<Map<String, Object>> rows = jdbc.query("""
                select id, kind, value_hash, display, status, visibility, training_label, note, updated_at
                from threat_indicator where id = ? and organization_id = ?
                """, (rs, n) -> row(rs, true), id, organizationId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Indicador não encontrado.");
        return rows.get(0);
    }

    private static Map<String, Object> row(java.sql.ResultSet rs, boolean owner) throws java.sql.SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", rs.getString("id"));
        out.put("kind", rs.getString("kind"));
        out.put("valueHash", rs.getString("value_hash"));
        if (owner) out.put("display", rs.getString("display"));
        out.put("status", rs.getString("status"));
        out.put("visibility", rs.getString("visibility"));
        out.put("trainingLabel", false);
        out.put("fetchesUrl", false);
        return out;
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
