package com.contextshield.core.service;

import com.contextshield.core.domain.AssessmentRecord;
import com.contextshield.core.domain.Transaction;
import com.contextshield.core.repo.AssessmentRecordRepository;
import com.contextshield.core.repo.TransactionRepository;
import com.contextshield.core.web.ApiException;
import com.contextshield.core.config.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Roda o motor de verdade com os pesos de uma política e não grava a decisão na compra. */
@Service
public class PolicySimulation {

    private final RiskPolicyService policies;
    private final EngineInputBuilder inputs;
    private final RiskEngineClient engine;
    private final TransactionRepository transactions;
    private final AssessmentRecordRepository assessments;
    private final OrganizationService organizations;
    private final JdbcTemplate jdbc;

    public PolicySimulation(RiskPolicyService policies, EngineInputBuilder inputs, RiskEngineClient engine,
                            TransactionRepository transactions, AssessmentRecordRepository assessments,
                            OrganizationService organizations, JdbcTemplate jdbc) {
        this.policies = policies;
        this.inputs = inputs;
        this.engine = engine;
        this.transactions = transactions;
        this.assessments = assessments;
        this.organizations = organizations;
        this.jdbc = jdbc;
    }

    public Map<String, Object> simulate(long policyId, String txId, CurrentUser user) {
        long organizationId = organizations.idOf(user);
        Transaction tx = transactions.findById(txId).orElseThrow(() -> ApiException.notFound("Transação"));
        organizations.requireSame(user, tx.getOrganizationId(), "Transação");
        Map<String, Object> input = inputs.build(tx, "initial", tx.getCreatedAt(), null);
        @SuppressWarnings("unchecked")
        Map<String, Object> config = new LinkedHashMap<>((Map<String, Object>) input.get("config"));
        config.put("weights", policies.get(policyId, organizationId).get("weights"));
        input.put("config", config);
        JsonNode again = engine.assess(input);
        AssessmentRecord original = assessments.findByTxIdOrderByIdAsc(txId).stream().findFirst().orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("txId", txId);
        out.put("policyId", policyId);
        out.put("originalScore", original == null ? null : original.getScore());
        out.put("originalDecision", original == null ? null : original.getDecision());
        out.put("simulatedScore", again.path("score").asInt());
        out.put("simulatedDecision", again.path("decision").asText());
        out.put("simulatedRule", again.path("ruleId").asText());
        out.put("applied", false);
        return out;
    }

    /** Religa a verificação seletiva só nesta chamada. Não grava decisão nem status. */
    public Map<String, Object> contextProbeImpact(CurrentUser user) {
        long organizationId = organizations.idOf(user);
        List<String> ids = jdbc.query("""
                select id from transactions
                where status = 'APPROVED' and coalesce(organization_id, 1) = ?
                order by created_at desc limit 25
                """, (rs, n) -> rs.getString(1), organizationId);
        int examined = 0;
        int wouldAsk = 0;
        String unavailable = null;
        for (String id : ids) {
            Transaction tx = transactions.findById(id).orElse(null);
            if (tx == null) continue;
            Map<String, Object> input = inputs.build(tx, "initial", tx.getCreatedAt(), null);
            @SuppressWarnings("unchecked")
            Map<String, Object> config = new LinkedHashMap<>((Map<String, Object>) input.get("config"));
            @SuppressWarnings("unchecked")
            Map<String, Object> flags = new LinkedHashMap<>((Map<String, Object>) config.get("flags"));
            flags.put("context-when-unknown", true);
            config.put("flags", flags);
            config.remove("contextProbeSuppressed");
            input.put("config", config);
            try {
                JsonNode again = engine.assess(input);
                examined++;
                if ("R12".equals(again.path("ruleId").asText())) wouldAsk++;
            } catch (RuntimeException e) {
                unavailable = "O motor não respondeu. Nada foi gravado.";
                break;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("examined", examined);
        out.put("wouldRequestVerification", wouldAsk);
        out.put("rate", examined == 0 ? null : Math.round(wouldAsk * 1000.0 / examined) / 1000.0);
        out.put("persisted", false);
        out.put("limit", ids.size());
        out.put("note", "A simulação liga a verificação seletiva só na memória, nas últimas compras aprovadas da organização. Compra habitual sem sinal de atenção continua aprovada. Nada é gravado.");
        if (unavailable != null) out.put("unavailable", unavailable);
        return out;
    }
}
