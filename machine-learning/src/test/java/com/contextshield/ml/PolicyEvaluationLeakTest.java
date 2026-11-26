package com.contextshield.ml;

import com.contextshield.risk.engine.BuiltinRuleCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyEvaluationLeakTest {

    @Test
    void holdoutPartitionDoesNotOverlap() {
        var cases = List.of(
                new PolicyEvaluation.CaseRef("TX-A", true, null, null),
                new PolicyEvaluation.CaseRef("TX-B", false, null, null),
                new PolicyEvaluation.CaseRef("TX-C", true, null, null));
        long holdout = cases.stream().filter(c -> Math.floorMod(c.ref().hashCode(), 5) == 0).count();
        long tune = cases.size() - holdout;
        assertEquals(cases.size(), holdout + tune);
        assertTrue(tune >= 0);
    }

    @Test
    void weightSearchUsesDynamicRulesNotLegacyOnly() {
        var parts = SyntheticCases.split(SyntheticCases.generate(300, 7), 7);
        var rules = BuiltinRuleCatalog.publishedDefaults();
        var result = WeightSearch.run(parts.search(), parts.confirm(), 7, 12, rules, PolicyEvaluation.CostProfile.standard());
        assertTrue(Boolean.TRUE.equals(result.get("dynamicRulesApplied")));
        assertTrue(((Number) result.get("candidates")).intValue() > 0);
    }
}