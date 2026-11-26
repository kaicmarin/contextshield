package com.contextshield.core.service;

import com.contextshield.core.web.ApiException;
import com.contextshield.risk.engine.BuiltinRuleCatalog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Regras do motor (R1–R12 embutidas + customizadas como R13). Publicação não exige reinício. */
@Service
public class RiskRuleService {

    private static final Set<String> OUTCOMES = Set.of("APPROVE", "APPROVE_WITH_ALERT", "REQUEST_CONTEXT", "INTERVENE", "BLOCK");
    private static final Set<String> KINDS = Set.of(
            "SCORE_GTE_WEIGHT", "RECALC_FLAG_SIGNALS", "LEVEL_INCONCLUSIVE_INITIAL_CTX", "LEVEL_INCONCLUSIVE_INITIAL_APPROVE",
            "LEVEL_INCONCLUSIVE_RECALC_INTERVENE", "LEVEL_INCONCLUSIVE_RECALC_ALERT", "LEVEL_INCONCLUSIVE_RECALC_APPROVE",
            "BAND_HIGH_RECALC", "BAND_HIGH_INITIAL", "ORIGIN_GUIDANCE_CTX", "BAND_MEDIUM_ALERT", "CONTEXT_PROBE",
            "DEFAULT_APPROVE", "SCORE_SIGNAL");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final OrganizationService organizations;

    public RiskRuleService(JdbcTemplate jdbc, ObjectMapper mapper, AuditService audit, OrganizationService organizations) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.audit = audit;
        this.organizations = organizations;
    }

    public List<Map<String, Object>> list(long organizationId) {
        return jdbc.query("""
                select id, rule_key, display_key, name, summary, priority, status, kind, params_json, outcome, version, builtin,
                       note, created_by, created_at, published_by, published_at, organization_id
                from risk_engine_rule where organization_id = ? order by priority asc, id desc
                """, (rs, n) -> row(rs), organizationId);
    }

    public Map<String, Object> catalog(long organizationId) {
        List<Map<String, Object>> published = activePublishedRows(organizationId);
        return Map.of(
                "kinds", kindCatalog(),
                "outcomes", OUTCOMES,
                "publishedCount", published.size(),
                "rules", published);
    }

    public Map<String, Object> get(long id, long organizationId) {
        Map<String, Object> row = one(id);
        if (((Number) row.get("organizationId")).longValue() != organizationId) throw ApiException.notFound("Regra");
        row.put("events", events(id));
        return row;
    }

    public List<Map<String, Object>> activeRulePayload(long organizationId) {
        List<Map<String, Object>> published = activePublishedRows(organizationId).stream().map(this::toEngine).toList();
        if (published.isEmpty()) return emergencyCatalogPayload();
        return published;
    }

    /** Sem regras publicadas no banco: envia catálogo equivalente ao seed (ainda dinâmico, não legacyDecide). */
    public List<Map<String, Object>> emergencyCatalogPayload() {
        return BuiltinRuleCatalog.publishedDefaults().stream().map(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", r.key());
            row.put("displayKey", r.displayKey());
            row.put("name", r.name());
            row.put("summary", r.summary());
            row.put("priority", r.priority());
            row.put("kind", r.kind());
            row.put("params", r.params() == null ? Map.of() : r.params());
            row.put("outcome", r.outcome());
            return row;
        }).toList();
    }

    public Map<String, Object> create(String ruleKey, String name, String summary, int priority, String kind,
                                      Map<String, Object> params, String outcome, String note, String actor) {
        validateKey(ruleKey);
        validateKind(kind, params, outcome);
        long organizationId = organizations.idOfEmail(actor);
        if (jdbc.queryForObject("select count(*) from risk_engine_rule where organization_id = ? and rule_key = ?",
                Integer.class, organizationId, ruleKey) > 0)
            throw ApiException.conflict("Já existe regra com a chave " + ruleKey + ". Crie uma nova versão.");
        int version = 1;
        long id = insert(ruleKey, ruleKey, name, summary, priority, "DRAFT", kind, params, outcome, version, false, note,
                organizationId, actor, null);
        event(id, "CREATED", actor, "Rascunho " + ruleKey);
        audit.log(actor, "RULE_DRAFT", ruleKey, name);
        return get(id, organizationId);
    }

    public Map<String, Object> update(long id, String name, String summary, int priority, String kind,
                                      Map<String, Object> params, String outcome, String note, String actor) {
        Map<String, Object> row = owned(id, actor);
        if (!List.of("DRAFT", "REJECTED").contains(String.valueOf(row.get("status"))))
            throw ApiException.conflict("Só rascunho ou versão rejeitada pode ser editada.");
        validateKind(kind, params, outcome);
        jdbc.update("""
                update risk_engine_rule set name = ?, summary = ?, priority = ?, kind = ?, params_json = ?, outcome = ?, note = ?, status = 'DRAFT'
                where id = ?
                """, name, summary, priority, kind, write(params), outcome, note, id);
        event(id, "EDITED", actor, "Regra atualizada");
        audit.log(actor, "RULE_EDITED", String.valueOf(row.get("ruleKey")), name);
        return get(id, ((Number) row.get("organizationId")).longValue());
    }

    public Map<String, Object> newVersion(long ruleKeyId, String actor) {
        Map<String, Object> row = owned(ruleKeyId, actor);
        String ruleKey = String.valueOf(row.get("ruleKey"));
        long organizationId = ((Number) row.get("organizationId")).longValue();
        int version = jdbc.queryForObject("select coalesce(max(version), 0) + 1 from risk_engine_rule where organization_id = ? and rule_key = ?",
                Integer.class, organizationId, ruleKey);
        long id = insert(ruleKey, String.valueOf(row.get("displayKey")), String.valueOf(row.get("name")),
                String.valueOf(row.get("summary")), ((Number) row.get("priority")).intValue(), "DRAFT",
                String.valueOf(row.get("kind")), readMap(String.valueOf(row.get("paramsJson"))),
                String.valueOf(row.get("outcome")), version, ((Boolean) row.get("builtin")), "Cópia para edição",
                organizationId, actor, null);
        event(id, "COPIED", actor, "Origem " + ruleKeyId);
        return get(id, organizationId);
    }

    public Map<String, Object> submit(long id, String actor) {
        owned(id, actor);
        require(id, "DRAFT", "Envie um rascunho.");
        jdbc.update("update risk_engine_rule set status = 'PENDING' where id = ?", id);
        event(id, "SUBMITTED", actor, "Aguardando aprovação");
        audit.log(actor, "RULE_SUBMITTED", String.valueOf(id), null);
        return get(id, organizations.idOfEmail(actor));
    }

    public Map<String, Object> approve(long id, String actor) {
        owned(id, actor);
        require(id, "PENDING", "Aprove uma versão enviada.");
        jdbc.update("update risk_engine_rule set status = 'APPROVED' where id = ?", id);
        event(id, "APPROVED", actor, "Aprovada");
        audit.log(actor, "RULE_APPROVED", String.valueOf(id), null);
        return get(id, organizations.idOfEmail(actor));
    }

    public Map<String, Object> reject(long id, String reason, String actor) {
        owned(id, actor);
        require(id, "PENDING", "Rejeite uma versão enviada.");
        jdbc.update("update risk_engine_rule set status = 'REJECTED', note = ? where id = ?", reason, id);
        event(id, "REJECTED", actor, reason);
        audit.log(actor, "RULE_REJECTED", String.valueOf(id), reason);
        return get(id, organizations.idOfEmail(actor));
    }

    public Map<String, Object> publish(long id, String actor) {
        Map<String, Object> row = owned(id, actor);
        if (!"APPROVED".equals(row.get("status"))) throw ApiException.conflict("Publique só uma versão aprovada.");
        String ruleKey = String.valueOf(row.get("ruleKey"));
        long organizationId = ((Number) row.get("organizationId")).longValue();
        jdbc.update("""
                update risk_engine_rule set status = 'ARCHIVED'
                where organization_id = ? and rule_key = ? and status = 'PUBLISHED'
                """, organizationId, ruleKey);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbc.update("update risk_engine_rule set status = 'PUBLISHED', published_by = ?, published_at = ? where id = ?",
                actor, Timestamp.valueOf(now), id);
        event(id, "PUBLISHED", actor, "Regra " + ruleKey + " em produção");
        audit.log(actor, "RULE_PUBLISHED", ruleKey, String.valueOf(row.get("version")));
        return get(id, organizationId);
    }

    public Map<String, Object> disable(long id, String actor) {
        Map<String, Object> row = owned(id, actor);
        if (!"PUBLISHED".equals(row.get("status"))) throw ApiException.conflict("Desative só uma regra publicada.");
        jdbc.update("update risk_engine_rule set status = 'DISABLED' where id = ?", id);
        event(id, "DISABLED", actor, "Regra desligada");
        audit.log(actor, "RULE_DISABLED", String.valueOf(row.get("ruleKey")), null);
        return get(id, ((Number) row.get("organizationId")).longValue());
    }

    public Map<String, Object> enable(long id, String actor) {
        Map<String, Object> row = owned(id, actor);
        if (!"DISABLED".equals(row.get("status"))) throw ApiException.conflict("Ative uma regra desligada.");
        String ruleKey = String.valueOf(row.get("ruleKey"));
        long organizationId = ((Number) row.get("organizationId")).longValue();
        jdbc.update("update risk_engine_rule set status = 'DISABLED' where organization_id = ? and rule_key = ? and status = 'PUBLISHED'",
                organizationId, ruleKey);
        jdbc.update("update risk_engine_rule set status = 'PUBLISHED', published_by = ?, published_at = ? where id = ?",
                actor, Timestamp.valueOf(LocalDateTime.now().withNano(0)), id);
        event(id, "ENABLED", actor, "Regra religada");
        audit.log(actor, "RULE_ENABLED", ruleKey, null);
        return get(id, organizationId);
    }

    public Map<String, Object> rollback(long id, String actor) {
        Map<String, Object> row = owned(id, actor);
        if (!"ARCHIVED".equals(row.get("status"))) throw ApiException.conflict("Restaurar a partir de uma versão arquivada.");
        String ruleKey = String.valueOf(row.get("ruleKey"));
        long organizationId = ((Number) row.get("organizationId")).longValue();
        jdbc.update("update risk_engine_rule set status = 'ARCHIVED' where organization_id = ? and rule_key = ? and status = 'PUBLISHED'",
                organizationId, ruleKey);
        int version = jdbc.queryForObject("select coalesce(max(version), 0) + 1 from risk_engine_rule where organization_id = ? and rule_key = ?",
                Integer.class, organizationId, ruleKey);
        long copy = insert(ruleKey, String.valueOf(row.get("displayKey")), String.valueOf(row.get("name")),
                String.valueOf(row.get("summary")), ((Number) row.get("priority")).intValue(), "PUBLISHED",
                String.valueOf(row.get("kind")), readMap(String.valueOf(row.get("paramsJson"))),
                String.valueOf(row.get("outcome")), version, ((Boolean) row.get("builtin")), "Rollback da versão " + row.get("version"),
                organizationId, actor, actor);
        event(copy, "ROLLBACK", actor, "Origem " + id);
        audit.log(actor, "RULE_ROLLBACK", ruleKey, "origem " + id);
        return get(copy, organizationId);
    }

    private List<Map<String, Object>> activePublishedRows(long organizationId) {
        return jdbc.query("""
                select id, rule_key, display_key, name, summary, priority, status, kind, params_json, outcome, version, builtin,
                       note, created_by, created_at, published_by, published_at, organization_id
                from risk_engine_rule where organization_id = ? and status = 'PUBLISHED' order by priority asc, id asc
                """, (rs, n) -> row(rs), organizationId);
    }

    private Map<String, Object> toEngine(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", row.get("ruleKey"));
        out.put("displayKey", row.get("displayKey"));
        out.put("name", row.get("name"));
        out.put("summary", row.get("summary"));
        out.put("priority", row.get("priority"));
        out.put("kind", row.get("kind"));
        out.put("params", row.get("params"));
        out.put("outcome", row.get("outcome"));
        return out;
    }

    private List<Map<String, Object>> kindCatalog() {
        List<Map<String, Object>> kinds = new ArrayList<>();
        kinds.add(Map.of("id", "SCORE_SIGNAL", "label", "Score e sinal", "hint", "Bloqueia ou intervém quando o score passa de um limiar e um sinal está presente (ex.: R13)."));
        kinds.add(Map.of("id", "SCORE_GTE_WEIGHT", "label", "Score acima de faixa", "hint", "Usa peso THRESHOLD_*."));
        kinds.add(Map.of("id", "CONTEXT_PROBE", "label", "Pedido de contexto seletivo", "hint", "Equivalente à R12."));
        return kinds;
    }

    private void validateKey(String ruleKey) {
        if (ruleKey == null || !ruleKey.matches("^R\\d{1,3}([\\-][0-9]+)?$"))
            throw ApiException.badRequest("Use uma chave como R13 ou R13-1.");
    }

    private void validateKind(String kind, Map<String, Object> params, String outcome) {
        if (!KINDS.contains(kind)) throw ApiException.badRequest("Tipo de regra desconhecido: " + kind);
        if (!OUTCOMES.contains(outcome)) throw ApiException.badRequest("Decisão inválida.");
        if ("SCORE_SIGNAL".equals(kind)) {
            if (params == null || (!params.containsKey("minScore") && !params.containsKey("weightKey")))
                throw ApiException.badRequest("Informe minScore ou weightKey.");
            if (!params.containsKey("signal")) throw ApiException.badRequest("Informe o sinal.");
        }
    }

    private Map<String, Object> owned(long id, String actor) {
        Map<String, Object> row = one(id);
        if (((Number) row.get("organizationId")).longValue() != organizations.idOfEmail(actor))
            throw ApiException.notFound("Regra");
        return row;
    }

    private void require(long id, String status, String message) {
        if (!status.equals(one(id).get("status"))) throw ApiException.conflict(message);
    }

    private Map<String, Object> one(long id) {
        List<Map<String, Object>> rows = jdbc.query("""
                select id, rule_key, display_key, name, summary, priority, status, kind, params_json, outcome, version, builtin,
                       note, created_by, created_at, published_by, published_at, organization_id
                from risk_engine_rule where id = ?
                """, (rs, n) -> row(rs), id);
        if (rows.isEmpty()) throw ApiException.notFound("Regra");
        return rows.get(0);
    }

    private Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("ruleKey", rs.getString("rule_key"));
        row.put("displayKey", rs.getString("display_key"));
        row.put("name", rs.getString("name"));
        row.put("summary", rs.getString("summary"));
        row.put("priority", rs.getInt("priority"));
        row.put("status", rs.getString("status"));
        row.put("kind", rs.getString("kind"));
        row.put("params", readMap(rs.getString("params_json")));
        row.put("paramsJson", rs.getString("params_json"));
        row.put("outcome", rs.getString("outcome"));
        row.put("version", rs.getInt("version"));
        row.put("builtin", rs.getBoolean("builtin"));
        row.put("note", rs.getString("note"));
        row.put("createdBy", rs.getString("created_by"));
        row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
        row.put("publishedBy", rs.getString("published_by"));
        Timestamp published = rs.getTimestamp("published_at");
        row.put("publishedAt", published == null ? null : published.toLocalDateTime());
        row.put("organizationId", rs.getLong("organization_id"));
        return row;
    }

    private List<Map<String, Object>> events(long id) {
        return jdbc.query("select action, actor, detail, at from risk_engine_rule_event where rule_id = ? order by id",
                (rs, n) -> Map.of("action", rs.getString(1), "actor", rs.getString(2), "detail", String.valueOf(rs.getString(3)),
                        "at", rs.getTimestamp(4).toLocalDateTime()), id);
    }

    private void event(long id, String action, String actor, String detail) {
        jdbc.update("insert into risk_engine_rule_event (rule_id, action, actor, detail, at) values (?, ?, ?, ?, ?)",
                id, action, actor, detail, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
    }

    private long insert(String ruleKey, String displayKey, String name, String summary, int priority, String status,
                        String kind, Map<String, Object> params, String outcome, int version, boolean builtin, String note,
                        long organizationId, String actor, String publisher) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into risk_engine_rule (organization_id, rule_key, display_key, name, summary, priority, status, kind,
                    params_json, outcome, version, builtin, note, created_by, created_at, published_by, published_at)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, organizationId);
            ps.setString(2, ruleKey);
            ps.setString(3, displayKey);
            ps.setString(4, name);
            ps.setString(5, summary);
            ps.setInt(6, priority);
            ps.setString(7, status);
            ps.setString(8, kind);
            ps.setString(9, write(params));
            ps.setString(10, outcome);
            ps.setInt(11, version);
            ps.setBoolean(12, builtin);
            ps.setString(13, note);
            ps.setString(14, actor);
            ps.setTimestamp(15, Timestamp.valueOf(now));
            ps.setString(16, publisher);
            ps.setTimestamp(17, publisher == null ? null : Timestamp.valueOf(now));
            return ps;
        }, keys);
        return GeneratedIds.of(keys);
    }

    private Map<String, Object> readMap(String json) {
        try {
            if (json == null || json.isBlank()) return Map.of();
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String write(Map<String, Object> params) {
        try {
            return mapper.writeValueAsString(params == null ? Map.of() : params);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Usado em testes de equivalência: catálogo embutido do motor. */
    public static int builtinRuleCount() {
        return BuiltinRuleCatalog.publishedDefaults().size();
    }

    public Map<String, Object> draftFromMlSuggestion(long suggestionId, String actor) {
        List<Map<String, Object>> rows = jdbc.query("""
                select id, organization_id, rule_key, display_key, name, summary, priority, kind, params_json, outcome, status, note
                from ml_rule_suggestion where id = ?
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("organizationId", rs.getLong("organization_id"));
            row.put("ruleKey", rs.getString("rule_key"));
            row.put("displayKey", rs.getString("display_key"));
            row.put("name", rs.getString("name"));
            row.put("summary", rs.getString("summary"));
            row.put("priority", rs.getInt("priority"));
            row.put("kind", rs.getString("kind"));
            row.put("params", readMap(rs.getString("params_json")));
            row.put("outcome", rs.getString("outcome"));
            row.put("status", rs.getString("status"));
            row.put("note", rs.getString("note"));
            return row;
        }, suggestionId);
        if (rows.isEmpty()) throw ApiException.notFound("Sugestão de regra");
        Map<String, Object> sug = rows.get(0);
        long organizationId = ((Number) sug.get("organizationId")).longValue();
        if (organizationId != organizations.idOfEmail(actor)) throw ApiException.notFound("Sugestão de regra");
        if (!"SUGGESTED".equals(sug.get("status"))) throw ApiException.conflict("Só uma sugestão pendente vira rascunho.");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) sug.get("params");
        Map<String, Object> created = create(String.valueOf(sug.get("ruleKey")), String.valueOf(sug.get("name")),
                String.valueOf(sug.get("summary")), ((Number) sug.get("priority")).intValue(),
                String.valueOf(sug.get("kind")), params, String.valueOf(sug.get("outcome")),
                "Rascunho a partir da sugestão do laboratório #" + suggestionId, actor);
        jdbc.update("update ml_rule_suggestion set status = 'DRAFTED' where id = ?", suggestionId);
        audit.log(actor, "RULE_SUGGESTION_DRAFT", String.valueOf(sug.get("ruleKey")), "sugestão " + suggestionId);
        return created;
    }
}