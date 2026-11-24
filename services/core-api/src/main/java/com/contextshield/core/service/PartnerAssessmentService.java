package com.contextshield.core.service;

import com.contextshield.core.config.CurrentUser;
import com.contextshield.core.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Avaliação para outra aplicação. A organização sai do usuário autenticado.
 * A decisão gravada é a do motor. O modelo, se houver, fica só no modo sombra.
 */
@Service
public class PartnerAssessmentService {

    private static final int LIMIT = 30;
    private final JdbcTemplate jdbc;
    private final OrganizationService organizations;
    private final RiskPolicyService policies;
    private final EngineSettings settings;
    private final RiskEngineClient engine;
    private final MachineLearningService learning;
    private final AuditService audit;
    private final ConcurrentHashMap<Long, ArrayDeque<Long>> hits = new ConcurrentHashMap<>();

    public PartnerAssessmentService(JdbcTemplate jdbc, OrganizationService organizations, RiskPolicyService policies,
                                    EngineSettings settings, RiskEngineClient engine, MachineLearningService learning,
                                    AuditService audit) {
        this.jdbc = jdbc;
        this.organizations = organizations;
        this.policies = policies;
        this.settings = settings;
        this.engine = engine;
        this.learning = learning;
        this.audit = audit;
    }

    public Map<String, Object> assess(CurrentUser user, String idempotencyKey, Map<String, Object> body) {
        if (!user.isStaff()) throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "A API de integração é da equipe da organização.");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 80)
            throw ApiException.badRequest("Envie o cabeçalho Idempotency-Key, com até 80 caracteres.");
        body.remove("organizationId");
        body.remove("organization");
        require(body, "amount", "customer", "device", "address", "declaredOrigin");
        long orgId = organizations.idOf(user);
        limit(orgId);
        Map<String, Object> existing = find(orgId, idempotencyKey);
        if (existing != null) {
            existing.put("idempotentReplay", true);
            return existing;
        }
        Map<String, Object> org = organizations.current(user);
        String profile = String.valueOf(org.get("profile"));
        Map<String, Object> input = new LinkedHashMap<>(body);
        input.putIfAbsent("stage", "initial");
        input.putIfAbsent("at", LocalDateTime.now().withNano(0).toString());
        input.putIfAbsent("unavailable", List.of());
        Map<String, Object> config = new LinkedHashMap<>(settings.config());
        config.put("weights", policies.activeWeights(profile, orgId));
        input.put("config", config);

        JsonNode result = engine.assess(input);
        String decision = result.path("decision").asText();
        Map<String, Object> ml = learning.shadow("PARCEIRO-" + orgId + "-" + idempotencyKey, input, decision, "initial", orgId);
        long id = insert(orgId, idempotencyKey, result, ml);
        audit.log(user.email(), "PARTNER_ASSESS", String.valueOf(id), decision + " · org " + org.get("code"));
        return view(id, org, result, ml, false);
    }

    public Map<String, Object> get(CurrentUser user, long id) {
        if (!user.isStaff()) throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "A API de integração é da equipe da organização.");
        long orgId = organizations.idOf(user);
        Integer count = jdbc.queryForObject("select count(*) from partner_call where id = ? and organization_id = ?", Integer.class, id, orgId);
        if (count == null || count == 0) throw ApiException.notFound("Avaliação");
        return jdbc.query("""
                select id, score, decision, rule_id, ml_prediction, ml_probability, created_at
                from partner_call where id = ?
                """, rs -> {
            rs.next();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("score", rs.getInt("score"));
            row.put("decision", rs.getString("decision"));
            row.put("ruleId", rs.getString("rule_id"));
            row.put("machineLearning", ml(rs.getString("ml_prediction"), rs.getObject("ml_probability")));
            row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
            row.put("organization", organizations.current(user));
            return row;
        }, id);
    }

    private Map<String, Object> find(long orgId, String key) {
        List<Map<String, Object>> rows = jdbc.query("""
                select id, score, decision, rule_id, ml_prediction, ml_probability
                from partner_call where organization_id = ? and idempotency_key = ?
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("score", rs.getInt("score"));
            row.put("decision", rs.getString("decision"));
            row.put("ruleId", rs.getString("rule_id"));
            row.put("machineLearning", ml(rs.getString("ml_prediction"), rs.getObject("ml_probability")));
            return row;
        }, orgId, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long insert(long orgId, String key, JsonNode result, Map<String, Object> ml) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into partner_call (organization_id, idempotency_key, score, decision, rule_id, payload,
                        ml_prediction, ml_probability, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, orgId);
            ps.setString(2, key);
            ps.setInt(3, result.path("score").asInt());
            ps.setString(4, result.path("decision").asText());
            ps.setString(5, result.path("ruleId").asText());
            ps.setString(6, result.toString());
            ps.setString(7, ml.get("prediction") == null ? null : String.valueOf(ml.get("prediction")));
            if (ml.get("probability") instanceof Number n) ps.setDouble(8, n.doubleValue());
            else ps.setNull(8, java.sql.Types.DOUBLE);
            ps.setTimestamp(9, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
            return ps;
        }, keys);
        return GeneratedIds.of(keys);
    }

    private Map<String, Object> view(long id, Map<String, Object> org, JsonNode result, Map<String, Object> ml, boolean replay) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("organization", org);
        out.put("score", result.path("score").asInt());
        out.put("band", result.path("band").asText());
        out.put("decision", result.path("decision").asText());
        out.put("ruleId", result.path("ruleId").asText());
        out.put("ruleText", result.path("ruleText").asText());
        out.put("signals", result.path("signals"));
        out.put("machineLearning", ml);
        out.put("idempotentReplay", replay);
        return out;
    }

    private static Map<String, Object> ml(String prediction, Object probability) {
        Map<String, Object> ml = new LinkedHashMap<>();
        ml.put("mode", "shadow");
        ml.put("affectsDecision", false);
        ml.put("prediction", prediction);
        ml.put("probability", probability);
        ml.put("active", prediction != null);
        return ml;
    }

    private static void require(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            if (body.get(key) == null || String.valueOf(body.get(key)).isBlank())
                throw ApiException.badRequest("Falta o campo " + key + ".");
        }
    }

    private void limit(long orgId) {
        long now = System.currentTimeMillis();
        ArrayDeque<Long> window = hits.computeIfAbsent(orgId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > 60_000) window.removeFirst();
            if (window.size() >= LIMIT) throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Muitas avaliações neste minuto. O limite é " + LIMIT + ".");
            window.addLast(now);
        }
    }
}
