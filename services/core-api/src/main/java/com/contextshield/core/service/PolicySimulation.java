package com.contextshield.core.service;

import com.contextshield.core.domain.AssessmentRecord;
import com.contextshield.core.domain.Transaction;
import com.contextshield.core.repo.AssessmentRecordRepository;
import com.contextshield.core.repo.TransactionRepository;
import com.contextshield.core.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Roda o motor de verdade com os pesos de uma política e não grava a decisão na compra. */
@Service
public class PolicySimulation {

    private final RiskPolicyService policies;
    private final EngineInputBuilder inputs;
    private final RiskEngineClient engine;
    private final TransactionRepository transactions;
    private final AssessmentRecordRepository assessments;

    public PolicySimulation(RiskPolicyService policies, EngineInputBuilder inputs, RiskEngineClient engine,
                            TransactionRepository transactions, AssessmentRecordRepository assessments) {
        this.policies = policies;
        this.inputs = inputs;
        this.engine = engine;
        this.transactions = transactions;
        this.assessments = assessments;
    }

    public Map<String, Object> simulate(long policyId, String txId) {
        Transaction tx = transactions.findById(txId).orElseThrow(() -> ApiException.notFound("Transação"));
        Map<String, Object> input = inputs.build(tx, "initial", tx.getCreatedAt(), null);
        @SuppressWarnings("unchecked")
        Map<String, Object> config = new LinkedHashMap<>((Map<String, Object>) input.get("config"));
        config.put("weights", policies.get(policyId).get("weights"));
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
}
