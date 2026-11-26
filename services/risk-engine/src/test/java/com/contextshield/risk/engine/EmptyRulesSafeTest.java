package com.contextshield.risk.engine;

import com.contextshield.risk.model.AssessmentInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EmptyRulesSafeTest {

    @Test
    void zeroPublishedRulesRequestContextNotApprove() {
        var scored = new Policy.Scored(List.of(), 10, "LOW", "INCONCLUSIVE", 40, List.of());
        var outcome = DynamicPolicy.decide(scored, "initial", AssessmentInput.EngineConfig.defaults(), List.of());
        assertEquals("REQUEST_CONTEXT", outcome.decision());
        assertEquals("S0", outcome.ruleId());
    }

    @Test
    void policyDecideWithEmptyConfigRulesUsesCatalogNotLegacyApproveBypass() {
        var scored = new Policy.Scored(List.of(), 85, "CRITICAL", "CRITICAL", 90, List.of());
        var cfg = new AssessmentInput.EngineConfig(
                AssessmentInput.EngineConfig.defaults().flags(),
                AssessmentInput.EngineConfig.defaults().providers(),
                AssessmentInput.EngineConfig.defaults().weights(),
                List.of(),
                List.of());
        var outcome = Policy.decide(scored, "initial", cfg);
        assertEquals("BLOCK", outcome.decision());
        assertNotEquals("APPROVE", outcome.decision());
    }
}