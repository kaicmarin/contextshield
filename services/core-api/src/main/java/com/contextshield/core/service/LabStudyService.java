package com.contextshield.core.service;

import com.contextshield.core.domain.Transaction;
import com.contextshield.core.repo.TransactionRepository;
import com.contextshield.core.web.ApiException;
import com.contextshield.ml.PolicyEvaluation;
import com.contextshield.ml.SyntheticCases;
import com.contextshield.ml.WeightSearch;
import com.contextshield.risk.engine.RiskAssessor;
import com.contextshield.risk.engine.Weights;
import com.contextshield.risk.model.Assessment;
import com.contextshield.risk.model.AssessmentInput;
import com.contextshield.risk.model.DynamicRule;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** Estudos de pesos e regras: análise fundamentada, sem publicação automática. */
@Service
public class LabStudyService {

    private static final int MIN_REVIEWED_FOR_POLICY = 12;
    private static final int MIN_REVIEWED_FOR_RULES = 8;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final OrganizationService organizations;
    private final RiskPolicyService policies;
    private final RiskRuleService rules;
    private final EngineInputBuilder inputs;
    private final TransactionRepository transactions;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ml-study");
        t.setDaemon(true);
        return t;
    });

    public LabStudyService(JdbcTemplate jdbc, ObjectMapper mapper, AuditService audit, OrganizationService organizations,
                           RiskPolicyService policies, RiskRuleService rules, EngineInputBuilder inputs,
                           TransactionRepository transactions) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.audit = audit;
        this.organizations = organizations;
        this.policies = policies;
        this.rules = rules;
        this.inputs = inputs;
        this.transactions = transactions;
    }

    public Map<String, Object> labelsOverview(long organizationId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("syntheticNote", "Experimentos de treino usam operações geradas; métricas de arquivo são sintéticas.");
        List<Map<String, Object>> rows = jdbc.query("""
                select coalesce(k.origin_kind, 'UNKNOWN') origin, coalesce(k.review_state, 'REVIEWED') review_state,
                       k.label, count(*) total
                from known_outcome k
                left join transactions t on t.id = k.tx_id
                where coalesce(t.organization_id, 1) = ? or k.tx_id is null
                group by 1, 2, 3 order by 1, 2
                """, (rs, n) -> Map.of(
                "origin", rs.getString(1),
                "reviewState", rs.getString(2),
                "label", rs.getString(3),
                "total", rs.getInt(4)), organizationId);
        out.put("breakdown", rows);
        int reviewedFraudLegit = jdbc.queryForObject("""
                select count(*) from known_outcome k
                join transactions t on t.id = k.tx_id
                where k.label in ('FRAUD', 'LEGIT') and k.review_state = 'REVIEWED'
                  and k.origin_kind in ('VERIFIED', 'ANALYST') and coalesce(t.organization_id, 1) = ?
                """, Integer.class, organizationId);
        int pendingReports = jdbc.queryForObject("""
                select count(*) from known_outcome k
                join transactions t on t.id = k.tx_id
                where k.review_state = 'PENDING' and k.origin_kind = 'REPORT' and coalesce(t.organization_id, 1) = ?
                """, Integer.class, organizationId);
        out.put("reviewedFraudLegit", reviewedFraudLegit);
        out.put("pendingReports", pendingReports);
        out.put("sufficientForWeightStudy", reviewedFraudLegit >= MIN_REVIEWED_FOR_POLICY);
        out.put("sufficientForRuleStudy", reviewedFraudLegit >= MIN_REVIEWED_FOR_RULES);
        out.put("trainingLabelPolicy", "Só entram no treino rótulos REVIEWED com origem ANALYST/VERIFIED. Relatos PENDING e decisões do motor não viram rótulo.");
        return out;
    }

    public List<Map<String, Object>> studies(long organizationId) {
        return jdbc.query("""
                select id, kind, status, cost_profile, seed, created_by, created_at, finished_at, error
                from ml_study where organization_id = ? order by id desc limit 30
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("kind", rs.getString("kind"));
            row.put("status", rs.getString("status"));
            row.put("costProfile", rs.getString("cost_profile"));
            row.put("seed", rs.getObject("seed"));
            row.put("createdBy", rs.getString("created_by"));
            row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
            var fin = rs.getTimestamp("finished_at");
            row.put("finishedAt", fin == null ? null : fin.toLocalDateTime());
            row.put("error", rs.getString("error"));
            return row;
        }, organizationId);
    }

    public Map<String, Object> study(long id, long organizationId) {
        List<Map<String, Object>> rows = jdbc.query("""
                select id, organization_id, kind, status, cost_profile, seed, report_json, note, created_by, created_at, finished_at, error
                from ml_study where id = ?
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("organizationId", rs.getLong("organization_id"));
            row.put("kind", rs.getString("kind"));
            row.put("status", rs.getString("status"));
            row.put("costProfile", rs.getString("cost_profile"));
            row.put("seed", rs.getObject("seed"));
            row.put("report", readJson(rs.getString("report_json")));
            row.put("note", rs.getString("note"));
            row.put("createdBy", rs.getString("created_by"));
            row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
            var fin = rs.getTimestamp("finished_at");
            row.put("finishedAt", fin == null ? null : fin.toLocalDateTime());
            row.put("error", rs.getString("error"));
            return row;
        }, id);
        if (rows.isEmpty()) throw ApiException.notFound("Estudo");
        Map<String, Object> row = rows.get(0);
        if (((Number) row.get("organizationId")).longValue() != organizationId) throw ApiException.notFound("Estudo");
        return row;
    }

    public Map<String, Object> queueWeightStudy(long organizationId, String actor, long seed, String costProfileId) {
        if (!busy.compareAndSet(false, true)) throw ApiException.conflict("Já existe um estudo em execução.");
        PolicyEvaluation.CostProfile cost = costProfile(costProfileId);
        long id = insertStudy(organizationId, "WEIGHTS", cost.id(), seed, actor,
                "Busca de pesos com regras publicadas atuais. Não publica automaticamente.");
        audit.log(actor, "ML_WEIGHT_STUDY", String.valueOf(id), cost.description());
        pool.submit(() -> runWeightStudy(id, organizationId, seed, cost));
        return study(id, organizationId);
    }

    public Map<String, Object> queueRuleStudy(long organizationId, String actor) {
        if (!busy.compareAndSet(false, true)) throw ApiException.conflict("Já existe um estudo em execução.");
        long id = insertStudy(organizationId, "RULES", null, null, actor,
                "Análise de impacto das regras publicadas e sugestões em rascunho.");
        audit.log(actor, "ML_RULE_STUDY", String.valueOf(id), null);
        pool.submit(() -> runRuleStudy(id, organizationId));
        return study(id, organizationId);
    }

    public List<Map<String, Object>> ruleSuggestions(long organizationId) {
        return jdbc.query("""
                select id, study_id, rule_key, display_key, name, summary, priority, kind, params_json, outcome, status, note, evidence_json, created_at
                from ml_rule_suggestion where organization_id = ? order by id desc
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("studyId", rs.getObject("study_id"));
            row.put("ruleKey", rs.getString("rule_key"));
            row.put("displayKey", rs.getString("display_key"));
            row.put("name", rs.getString("name"));
            row.put("summary", rs.getString("summary"));
            row.put("priority", rs.getInt("priority"));
            row.put("kind", rs.getString("kind"));
            row.put("params", readJson(rs.getString("params_json")));
            row.put("outcome", rs.getString("outcome"));
            row.put("status", rs.getString("status"));
            row.put("note", rs.getString("note"));
            row.put("evidence", readJson(rs.getString("evidence_json")));
            row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
            return row;
        }, organizationId);
    }

    private void runWeightStudy(long id, long organizationId, long seed, PolicyEvaluation.CostProfile cost) {
        jdbc.update("update ml_study set status = 'RUNNING' where id = ?", id);
        try {
            Map<String, Integer> published = policies.activeWeights(policies.activeProfile(), organizationId);
            List<DynamicRule> dynamicRules = toRules(rules.activeRulePayload(organizationId));
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("dataMix", labelsOverview(organizationId));
            report.put("publishedWeights", published);
            report.put("publishedRulesCount", dynamicRules.size());
            report.put("leakageControl", "Busca usa só split search; confirmação é confirm; rótulos revisados usam hash de txId (20% holdout nunca entra na busca).");

            List<PolicyEvaluation.CaseRef> reviewed = reviewedCases(organizationId);
            Map<String, Object> baselineReviewed = Map.of();
            if (reviewed.size() >= MIN_REVIEWED_FOR_POLICY) {
                List<PolicyEvaluation.CaseRef> test = reviewed.stream().filter(c -> holdout(c.ref())).toList();
                List<PolicyEvaluation.CaseRef> tune = reviewed.stream().filter(c -> !holdout(c.ref())).toList();
                baselineReviewed = PolicyEvaluation.scoreCases(tune, published, dynamicRules, cost);
                Map<String, Object> holdoutBaseline = PolicyEvaluation.scoreCases(test, published, dynamicRules, cost);
                report.put("reviewedBaselineTune", baselineReviewed);
                report.put("reviewedBaselineHoldout", holdoutBaseline);
                report.put("reviewedExamples", reviewed.size());
            } else {
                report.put("reviewedInsufficient", true);
                report.put("reviewedMessage", "Menos de " + MIN_REVIEWED_FOR_POLICY
                        + " rótulos revisados: otimização principal usa sintético com regras publicadas.");
            }

            List<SyntheticCases.Case> synthetic = SyntheticCases.generate(Math.max(400, 300), seed);
            SyntheticCases.Parts parts = SyntheticCases.split(synthetic, seed);
            Map<String, Object> optimization = WeightSearch.run(parts.search(), parts.confirm(), seed, 48, dynamicRules, cost);
            Map<String, Object> baselineSynthetic = Map.of(
                    "search", optimization.get("baselineSearch"),
                    "confirm", optimization.get("baselineConfirm"),
                    "origin", "sintético",
                    "warning", SyntheticCases.LIMITATION);
            report.put("syntheticOptimization", optimization);
            report.put("syntheticBaseline", baselineSynthetic);
            report.put("costProfile", Map.of("id", cost.id(), "description", cost.description()));

            @SuppressWarnings("unchecked")
            Map<String, Integer> suggested = (Map<String, Integer>) optimization.get("suggestedWeights");
            boolean improved = Boolean.TRUE.equals(optimization.get("improvedOnBothSets"));
            report.put("recommendation", improved
                    ? "Há candidata de pesos que melhorou busca e manteve confirmação sintética, com regras dinâmicas atuais."
                    : "Nenhuma melhoria confiável; política publicada permanece.");

            jdbc.update("update ml_study set status = 'DONE', finished_at = ?, report_json = ? where id = ?",
                    Timestamp.valueOf(LocalDateTime.now().withNano(0)), mapper.writeValueAsString(report), id);
            if (improved && suggested != null && !suggested.isEmpty()) {
                jdbc.update("""
                        insert into weight_suggestion (experiment_id, weights_json, metrics_json, status, note, created_at, organization_id, cost_profile, baseline_json)
                        values (null, ?, ?, 'SUGGESTED', ?, ?, ?, ?, ?)
                        """, mapper.writeValueAsString(suggested), mapper.writeValueAsString(optimization),
                        optimization.get("note"), Timestamp.valueOf(LocalDateTime.now().withNano(0)), organizationId,
                        cost.id(), mapper.writeValueAsString(baselineSynthetic));
            }
        } catch (Exception e) {
            jdbc.update("update ml_study set status = 'FAILED', finished_at = ?, error = ? where id = ?",
                    Timestamp.valueOf(LocalDateTime.now().withNano(0)), trim(e.getMessage()), id);
        } finally {
            busy.set(false);
        }
    }

    private void runRuleStudy(long id, long organizationId) {
        jdbc.update("update ml_study set status = 'RUNNING' where id = ?", id);
        try {
            List<Map<String, Object>> published = rules.activeRulePayload(organizationId);
            List<DynamicRule> allRules = toRules(published);
            Map<String, Integer> weights = policies.activeWeights(policies.activeProfile(), organizationId);
            PolicyEvaluation.CostProfile cost = PolicyEvaluation.CostProfile.standard();
            List<PolicyEvaluation.CaseRef> cases = reviewedCases(organizationId);

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("publishedRules", published.size());
            report.put("insufficientData", cases.size() < MIN_REVIEWED_FOR_RULES);
            if (cases.size() < MIN_REVIEWED_FOR_RULES) {
                report.put("message", "Dados revisados insuficientes para medir impacto por regra com confiança.");
                finishStudy(id, report);
                return;
            }

            Map<String, Object> baseline = PolicyEvaluation.scoreCases(cases, weights, allRules, cost);
            report.put("baseline", baseline);

            List<Map<String, Object>> ruleStats = new ArrayList<>();
            RiskAssessor assessor = new RiskAssessor();
            for (Map<String, Object> ruleRow : published) {
                String key = String.valueOf(ruleRow.get("key"));
                List<DynamicRule> without = allRules.stream().filter(r -> !r.key().equals(key)).toList();
                Map<String, Object> simulated = PolicyEvaluation.scoreCases(cases, weights, without, cost);
                String display = String.valueOf(ruleRow.get("displayKey"));
                Map<String, Object> hit = jdbc.queryForMap("""
                        select count(*) as total,
                               sum(case when a.rule_id like ? then 1 else 0 end) as hits
                        from assessment a join transactions t on t.id = a.tx_id
                        where coalesce(t.organization_id, 1) = ? and a.stage = 'initial'
                        """, display + "%", organizationId);
                Map<String, Object> stat = new LinkedHashMap<>();
                stat.put("ruleKey", key);
                stat.put("displayKey", ruleRow.get("displayKey"));
                stat.put("name", ruleRow.get("name"));
                stat.put("priority", ruleRow.get("priority"));
                stat.put("assessmentHits", hit.get("hits"));
                stat.put("withoutRuleCost", simulated.get("cost"));
                stat.put("withoutRuleFalsePositives", simulated.get("falsePositives"));
                stat.put("withoutRuleFalseNegatives", simulated.get("falseNegatives"));
                stat.put("baselineCost", baseline.get("cost"));
                ruleStats.add(stat);
            }
            ruleStats.sort(Comparator.comparingInt(s -> -((Number) s.getOrDefault("assessmentHits", 0)).intValue()));
            report.put("rules", ruleStats);

            Map<String, Long> fpSignals = mistakeSignals(cases, weights, allRules, assessor, false);
            Map<String, Long> fnSignals = mistakeSignals(cases, weights, allRules, assessor, true);
            report.put("signalsInFalsePositives", topSignals(fpSignals));
            report.put("signalsInFalseNegatives", topSignals(fnSignals));

            List<Map<String, Object>> suggestions = proposeRules(fpSignals, fnSignals, organizationId);
            report.put("suggestedRules", suggestions.size());
            for (Map<String, Object> sug : suggestions) {
                jdbc.update("""
                        insert into ml_rule_suggestion (study_id, organization_id, rule_key, display_key, name, summary, priority, kind,
                        params_json, outcome, status, note, evidence_json, created_at)
                        values (?,?,?,?,?,?,?,?,?,?, 'SUGGESTED', ?, ?, ?)
                        """, id, organizationId, sug.get("ruleKey"), sug.get("displayKey"), sug.get("name"), sug.get("summary"),
                        sug.get("priority"), sug.get("kind"), write(sug.get("params")), sug.get("outcome"),
                        sug.get("note"), write(sug.get("evidence")), Timestamp.valueOf(LocalDateTime.now().withNano(0)));
            }
            report.put("note", "Sugestões ficam em ml_rule_suggestion até o admin criar rascunho em risk_engine_rule.");
            finishStudy(id, report);
        } catch (Exception e) {
            jdbc.update("update ml_study set status = 'FAILED', finished_at = ?, error = ? where id = ?",
                    Timestamp.valueOf(LocalDateTime.now().withNano(0)), trim(e.getMessage()), id);
        } finally {
            busy.set(false);
        }
    }

    private void finishStudy(long id, Map<String, Object> report) throws Exception {
        jdbc.update("update ml_study set status = 'DONE', finished_at = ?, report_json = ? where id = ?",
                Timestamp.valueOf(LocalDateTime.now().withNano(0)), mapper.writeValueAsString(report), id);
    }

    private List<Map<String, Object>> proposeRules(Map<String, Long> fpSignals, Map<String, Long> fnSignals, long organizationId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (fnSignals.getOrDefault("DESTINATION_IN_INCIDENT", 0L) >= 2) {
            out.add(suggestion("R13-LAB", "R13", "Destino em incidente (sugerida)",
                    "Laboratório: fraudes liberadas com destino citado em incidente", 15, "SCORE_SIGNAL",
                    Map.of("weightKey", "THRESHOLD_MEDIUM", "signal", "DESTINATION_IN_INCIDENT", "stage", "any",
                            "summary", "destino em incidente em falsos negativos"),
                    "BLOCK", "Gerada a partir de falsos negativos revisados; não publicada automaticamente.",
                    Map.of("source", "falseNegatives", "signal", "DESTINATION_IN_INCIDENT", "count", fnSignals.get("DESTINATION_IN_INCIDENT"))));
        }
        if (fpSignals.getOrDefault("NEW_DEVICE", 0L) >= 3) {
            out.add(suggestion("R14-LAB", "R14", "Revisar bloqueio em aparelho novo",
                    "Muitos falsos positivos com NEW_DEVICE; considere ajustar peso ou regra SCORE_SIGNAL", 55, "SCORE_SIGNAL",
                    Map.of("weightKey", "THRESHOLD_HIGH", "signal", "NEW_DEVICE", "stage", "initial",
                            "summary", "aparelho novo em falsos positivos"),
                    "REQUEST_CONTEXT", "Sugestão conservadora: pedir contexto em vez de bloquear.",
                    Map.of("source", "falsePositives", "signal", "NEW_DEVICE", "count", fpSignals.get("NEW_DEVICE"))));
        }
        return out;
    }

    private static Map<String, Object> suggestion(String ruleKey, String displayKey, String name, String summary, int priority,
                                                   String kind, Map<String, Object> params, String outcome, String note, Map<String, Object> evidence) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ruleKey", ruleKey);
        row.put("displayKey", displayKey);
        row.put("name", name);
        row.put("summary", summary);
        row.put("priority", priority);
        row.put("kind", kind);
        row.put("params", params);
        row.put("outcome", outcome);
        row.put("note", note);
        row.put("evidence", evidence);
        return row;
    }

    private Map<String, Long> mistakeSignals(List<PolicyEvaluation.CaseRef> cases, Map<String, Integer> weights,
                                             List<DynamicRule> rules, RiskAssessor assessor, boolean fraudMistake) {
        var config = new AssessmentInput.EngineConfig(
                AssessmentInput.EngineConfig.defaults().flags(),
                AssessmentInput.EngineConfig.defaults().providers(),
                weights, List.of(), rules);
        Map<String, Long> tally = new LinkedHashMap<>();
        for (PolicyEvaluation.CaseRef item : cases) {
            Assessment a = assessor.assess(item.initial().withConfig(config));
            String decision = a.decision();
            if ("REQUEST_CONTEXT".equals(decision) && item.answers() != null) {
                decision = assessor.assess(item.initial().withConfig(config).withStage("recalculated", item.answers())).decision();
            }
            boolean released = "APPROVE".equals(decision) || "APPROVE_WITH_ALERT".equals(decision);
            boolean fn = item.fraud() && released;
            boolean fp = !item.fraud() && !released;
            if (fraudMistake && !fn) continue;
            if (!fraudMistake && !fp) continue;
            a.signals().forEach(s -> tally.merge(s.id(), 1L, Long::sum));
        }
        return tally;
    }

    private static List<Map<String, Object>> topSignals(Map<String, Long> tally) {
        return tally.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(6)
                .map(e -> Map.<String, Object>of("signal", e.getKey(), "count", e.getValue()))
                .toList();
    }

    private List<PolicyEvaluation.CaseRef> reviewedCases(long organizationId) {
        List<PolicyEvaluation.CaseRef> out = new ArrayList<>();
        ObjectMapper om = PolicyEvaluation.mapper();
        jdbc.query("""
                select k.tx_id, k.label, v.answers
                from known_outcome k
                join transactions t on t.id = k.tx_id
                left join verification v on v.tx_id = k.tx_id
                where k.label in ('FRAUD', 'LEGIT') and k.review_state = 'REVIEWED'
                  and k.origin_kind in ('VERIFIED', 'ANALYST') and coalesce(t.organization_id, 1) = ?
                order by k.id
                """, rs -> {
            while (rs.next()) {
                String txId = rs.getString(1);
                boolean fraud = "FRAUD".equals(rs.getString(2));
                Transaction tx = transactions.findById(txId).orElse(null);
                if (tx == null) continue;
                Map<String, Object> built = inputs.build(tx, "initial", tx.getCreatedAt(), null);
                try {
                    AssessmentInput input = om.convertValue(built, AssessmentInput.class);
                    AssessmentInput.Answers answers = null;
                    String answersJson = rs.getString(3);
                    if (answersJson != null && !answersJson.isBlank()) {
                        answers = om.readValue(answersJson, AssessmentInput.Answers.class);
                    }
                    out.add(new PolicyEvaluation.CaseRef(txId, fraud, input, answers));
                } catch (Exception ignored) {
                }
            }
            return null;
        }, organizationId);
        return out;
    }

    private boolean holdout(String txId) {
        return Math.floorMod(txId.hashCode(), 5) == 0;
    }

    private List<DynamicRule> toRules(List<Map<String, Object>> payload) {
        return payload.stream().map(m -> new DynamicRule(
                String.valueOf(m.get("key")),
                String.valueOf(m.get("displayKey")),
                String.valueOf(m.get("name")),
                String.valueOf(m.get("summary") == null ? "" : m.get("summary")),
                ((Number) m.get("priority")).intValue(),
                String.valueOf(m.get("kind")),
                m.get("params") instanceof Map<?, ?> p ? castMap(p) : Map.of(),
                String.valueOf(m.get("outcome"))
        )).collect(Collectors.toCollection(ArrayList::new));
    }

  @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private PolicyEvaluation.CostProfile costProfile(String id) {
        if ("balanced".equalsIgnoreCase(id)) {
            return new PolicyEvaluation.CostProfile(3, 2, "balanced", "3×FN + 2×FP (menos peso em FN)");
        }
        if ("recall".equalsIgnoreCase(id)) {
            return new PolicyEvaluation.CostProfile(8, 1, "recall", "8×FN + 1×FP (prioriza não liberar fraude)");
        }
        return PolicyEvaluation.CostProfile.standard();
    }

    private long insertStudy(long organizationId, String kind, String costProfile, Long seed, String actor, String note) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into ml_study (organization_id, kind, status, cost_profile, seed, note, created_by, created_at)
                    values (?, ?, 'QUEUED', ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, organizationId);
            ps.setString(2, kind);
            ps.setString(3, costProfile);
            if (seed == null) ps.setObject(4, null);
            else ps.setLong(4, seed);
            ps.setString(5, note);
            ps.setString(6, actor);
            ps.setTimestamp(7, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
            return ps;
        }, keys);
        return GeneratedIds.of(keys);
    }

    private Object readJson(String json) {
        try {
            if (json == null || json.isBlank()) return null;
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private String write(Object value) throws Exception {
        return mapper.writeValueAsString(value);
    }

    private static String trim(String message) {
        if (message == null) return "erro";
        return message.length() > 480 ? message.substring(0, 480) : message;
    }
}