package com.contextshield.core.service;

import com.contextshield.core.web.ApiException;
import com.contextshield.risk.engine.Weights;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Políticas versionadas. O motor não lê o banco: quem envia os pesos é a core-api. */
@Service
public class RiskPolicyService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final OrganizationService organizations;
    private final String activeProfile;

    public RiskPolicyService(JdbcTemplate jdbc, ObjectMapper mapper, AuditService audit, OrganizationService organizations,
                             @Value("${contextshield.policy.profile:fintech}") String activeProfile) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.audit = audit;
        this.organizations = organizations;
        this.activeProfile = activeProfile;
    }

    public String activeProfile() {
        return activeProfile;
    }

    public Map<String, Integer> activeWeights() {
        return activeWeights(activeProfile, 1L);
    }

    public Map<String, Integer> activeWeights(String profile) {
        return activeWeights(profile, 1L);
    }

    public Map<String, Integer> activeWeights(String profile, long organizationId) {
        List<String> rows = jdbc.query("""
                select weights_json from risk_policy
                where profile = ? and status = 'PUBLISHED' and organization_id = ?
                order by id desc
                """, (rs, n) -> rs.getString(1), profile, organizationId);
        if (rows.isEmpty()) return Map.of();
        return read(rows.get(0));
    }

    public List<Map<String, Object>> list(long organizationId) {
        return jdbc.query("""
                select id, name, version, status, profile, organization_id, note, created_by, created_at, published_by, published_at
                from risk_policy where organization_id = ? order by id desc
                """, (rs, n) -> head(rs), organizationId);
    }

    public Map<String, Object> get(long id) {
        return present(one(id));
    }

    public Map<String, Object> get(long id, long organizationId) {
        Map<String, Object> row = one(id);
        if (((Number) row.get("organizationId")).longValue() != organizationId) throw ApiException.notFound("Política");
        return present(row);
    }

    public List<Map<String, Object>> catalog(long organizationId) {
        Map<String, Integer> current = Weights.merge(activeWeights(activeProfile, organizationId));
        return Weights.CATALOG.stream().map(bound -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", bound.key());
            item.put("label", bound.label());
            item.put("detail", bound.detail());
            item.put("value", current.get(bound.key()));
            item.put("fallback", bound.fallback());
            item.put("min", bound.min());
            item.put("max", bound.max());
            item.put("safetyFloor", bound.safetyFloor());
            return item;
        }).toList();
    }

    public Map<String, Object> create(String name, String profile, Map<String, Integer> weights, String note, String actor) {
        String useProfile = profile == null || profile.isBlank() ? activeProfile : profile.trim();
        if (!List.of("fintech", "marketplace", "ecommerce").contains(useProfile))
            throw ApiException.badRequest("Perfil desconhecido. Use fintech, marketplace ou ecommerce.");
        Map<String, Integer> stored = checked(weights);
        long organizationId = organizations.idOfEmail(actor);
        int version = jdbc.queryForObject("select coalesce(max(version), 0) + 1 from risk_policy where profile = ? and organization_id = ?",
                Integer.class, useProfile, organizationId);
        long id = insertPolicy(name, version, "DRAFT", useProfile, organizationId, stored, note, actor, null);
        event(id, "CREATED", actor, "Rascunho da versão " + version);
        audit.log(actor, "POLICY_DRAFT", String.valueOf(id), name);
        return get(id);
    }

    public Map<String, Object> update(long id, Map<String, Integer> weights, String note, String actor) {
        Map<String, Object> row = owned(id, actor);
        if (!List.of("DRAFT", "REJECTED").contains(row.get("status")))
            throw ApiException.conflict("Só um rascunho ou uma versão rejeitada pode ser editado.");
        Map<String, Integer> stored = checked(weights);
        jdbc.update("update risk_policy set weights_json = ?, note = ?, status = 'DRAFT' where id = ?", write(stored), note, id);
        event(id, "EDITED", actor, "Pesos atualizados");
        audit.log(actor, "POLICY_EDITED", String.valueOf(id), "pesos");
        return get(id);
    }

    public Map<String, Object> submit(long id, String actor) {
        owned(id, actor);
        require(id, "DRAFT", "Envie um rascunho.");
        jdbc.update("update risk_policy set status = 'PENDING' where id = ?", id);
        event(id, "SUBMITTED", actor, "Aguardando aprovação");
        audit.log(actor, "POLICY_SUBMITTED", String.valueOf(id), null);
        return get(id);
    }

    public Map<String, Object> approve(long id, String actor) {
        owned(id, actor);
        require(id, "PENDING", "Aprove uma versão enviada.");
        jdbc.update("update risk_policy set status = 'APPROVED' where id = ?", id);
        event(id, "APPROVED", actor, "Aprovada, ainda não publicada");
        audit.log(actor, "POLICY_APPROVED", String.valueOf(id), null);
        return get(id);
    }

    public Map<String, Object> reject(long id, String reason, String actor) {
        owned(id, actor);
        require(id, "PENDING", "Rejeite uma versão enviada.");
        jdbc.update("update risk_policy set status = 'REJECTED', note = ? where id = ?", reason, id);
        event(id, "REJECTED", actor, reason);
        audit.log(actor, "POLICY_REJECTED", String.valueOf(id), reason);
        return get(id);
    }

    public Map<String, Object> publish(long id, String actor) {
        Map<String, Object> row = owned(id, actor);
        if (!"APPROVED".equals(row.get("status"))) throw ApiException.conflict("Publique só uma versão já aprovada.");
        String profile = String.valueOf(row.get("profile"));
        long organizationId = ((Number) row.get("organizationId")).longValue();
        jdbc.update("update risk_policy set status = 'ARCHIVED' where profile = ? and organization_id = ? and status = 'PUBLISHED'", profile, organizationId);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbc.update("update risk_policy set status = 'PUBLISHED', published_by = ?, published_at = ? where id = ?", actor, Timestamp.valueOf(now), id);
        event(id, "PUBLISHED", actor, "Perfil " + profile);
        audit.log(actor, "POLICY_PUBLISHED", String.valueOf(id), profile);
        return get(id);
    }

    /** Volta uma versão que já esteve publicada. Continua exigindo papel de administração. */
    public Map<String, Object> rollback(long id, String actor) {
        Map<String, Object> row = owned(id, actor);
        if (!"ARCHIVED".equals(row.get("status"))) throw ApiException.conflict("A restauração parte de uma versão arquivada.");
        String profile = String.valueOf(row.get("profile"));
        long organizationId = ((Number) row.get("organizationId")).longValue();
        jdbc.update("update risk_policy set status = 'ARCHIVED' where profile = ? and organization_id = ? and status = 'PUBLISHED'", profile, organizationId);
        int version = jdbc.queryForObject("select coalesce(max(version), 0) + 1 from risk_policy where profile = ? and organization_id = ?",
                Integer.class, profile, organizationId);
        @SuppressWarnings("unchecked")
        Map<String, Integer> weights = (Map<String, Integer>) get(id).get("weights");
        long copy = insertPolicy(String.valueOf(row.get("name")) + " restaurada", version, "PUBLISHED", profile, organizationId, weights,
                "Restauração da versão " + row.get("version"), actor, actor);
        event(copy, "ROLLBACK", actor, "Cópia da política " + id);
        audit.log(actor, "POLICY_ROLLBACK", String.valueOf(copy), "origem " + id);
        return get(copy);
    }

    public Map<String, Object> compare(long left, long right, long organizationId) {
        get(left, organizationId);
        get(right, organizationId);
        Map<String, Integer> a = weightsOf(left);
        Map<String, Integer> b = weightsOf(right);
        List<Map<String, Object>> diffs = Weights.CATALOG.stream()
                .filter(bound -> !a.get(bound.key()).equals(b.get(bound.key())))
                .map(bound -> Map.<String, Object>of("key", bound.key(), "label", bound.label(), "left", a.get(bound.key()), "right", b.get(bound.key())))
                .toList();
        return Map.of("left", left, "right", right, "differences", diffs);
    }

    public long createFromSuggestion(String name, Map<String, Integer> weights, String actor) {
        Map<String, Object> created = create(name, activeProfile, weights, "Rascunho gerado por uma sugestão. Não está publicado.", actor);
        return ((Number) created.get("id")).longValue();
    }

    private Map<String, Integer> weightsOf(long id) {
        return Weights.merge(read(jdbc.queryForObject("select weights_json from risk_policy where id = ?", String.class, id)));
    }

    private Map<String, Integer> checked(Map<String, Integer> weights) {
        try {
            return Weights.merge(weights == null ? Map.of() : weights);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    private void require(long id, String status, String message) {
        if (!status.equals(one(id).get("status"))) throw ApiException.conflict(message);
    }

    private Map<String, Object> present(Map<String, Object> row) {
        long id = ((Number) row.get("id")).longValue();
        row.put("weights", Weights.merge(read(String.valueOf(row.remove("weightsJson")))));
        row.put("events", events(id));
        return row;
    }

    private Map<String, Object> owned(long id, String actor) {
        Map<String, Object> row = one(id);
        if (((Number) row.get("organizationId")).longValue() != organizations.idOfEmail(actor))
            throw ApiException.notFound("Política");
        return row;
    }

    private Map<String, Object> one(long id) {
        List<Map<String, Object>> rows = jdbc.query("""
                select id, name, version, status, profile, organization_id, weights_json, note, created_by, created_at, published_by, published_at
                from risk_policy where id = ?
                """, (rs, n) -> {
            Map<String, Object> row = head(rs);
            row.put("weightsJson", rs.getString("weights_json"));
            return row;
        }, id);
        if (rows.isEmpty()) throw ApiException.notFound("Política");
        return rows.get(0);
    }

    private Map<String, Object> head(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("name", rs.getString("name"));
        row.put("version", rs.getInt("version"));
        row.put("status", rs.getString("status"));
        row.put("profile", rs.getString("profile"));
        row.put("organizationId", rs.getLong("organization_id"));
        row.put("note", rs.getString("note"));
        row.put("createdBy", rs.getString("created_by"));
        row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
        row.put("publishedBy", rs.getString("published_by"));
        Timestamp published = rs.getTimestamp("published_at");
        row.put("publishedAt", published == null ? null : published.toLocalDateTime());
        return row;
    }

    private List<Map<String, Object>> events(long id) {
        return jdbc.query("select action, actor, detail, at from risk_policy_event where policy_id = ? order by id",
                (rs, n) -> Map.of("action", rs.getString(1), "actor", rs.getString(2), "detail", String.valueOf(rs.getString(3)), "at", rs.getTimestamp(4).toLocalDateTime()), id);
    }

    private void event(long id, String action, String actor, String detail) {
        jdbc.update("insert into risk_policy_event (policy_id, action, actor, detail, at) values (?, ?, ?, ?, ?)",
                id, action, actor, detail, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
    }

    private long insertPolicy(String name, int version, String status, String profile, long organizationId, Map<String, Integer> weights,
                              String note, String actor, String publisher) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into risk_policy (name, version, status, profile, organization_id, weights_json, note, created_by, created_at, published_by, published_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setInt(2, version);
            ps.setString(3, status);
            ps.setString(4, profile);
            ps.setLong(5, organizationId);
            ps.setString(6, write(weights));
            ps.setString(7, note);
            ps.setString(8, actor);
            ps.setTimestamp(9, Timestamp.valueOf(now));
            ps.setString(10, publisher);
            ps.setTimestamp(11, publisher == null ? null : Timestamp.valueOf(now));
            return ps;
        }, keys);
        return GeneratedIds.of(keys);
    }

    private Map<String, Integer> read(String json) {
        try {
            if (json == null || json.isBlank()) return Map.of();
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String write(Map<String, Integer> weights) {
        try {
            Map<String, Integer> overlay = new LinkedHashMap<>();
            Map<String, Integer> defaults = Weights.defaults();
            weights.forEach((key, value) -> {
                if (!value.equals(defaults.get(key))) overlay.put(key, value);
            });
            return mapper.writeValueAsString(overlay);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
