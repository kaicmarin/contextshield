package com.contextshield.ml;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightSearchImprovementSummaryTest {

    @Test
    void improvementSummaryWhenSearchImprovesConfirmHolds() {
        var synthetic = SyntheticCases.generate(400, 42);
        var parts = SyntheticCases.split(synthetic, 42);
        Map<String, Object> out = WeightSearch.run(parts.search(), parts.confirm(), 42, 48,
                List.of(), PolicyEvaluation.CostProfile.standard());
        assertTrue((Boolean) out.get("improvedOnBothSets"));
        String summary = String.valueOf(out.get("improvementSummary"));
        assertTrue(summary.contains("busca"), () -> "summary: " + summary);
        assertFalse(summary.toLowerCase().contains("ambas"), () -> summary);
        assertEquals(false, out.get("published"));
    }

    @Test
    void regressionStyleMetricsStillExposeCandidates() {
        var synthetic = SyntheticCases.generate(400, 99);
        var parts = SyntheticCases.split(synthetic, 99);
        Map<String, Object> out = WeightSearch.run(parts.search(), parts.confirm(), 99, 48,
                List.of(), PolicyEvaluation.CostProfile.standard());
        assertEquals(48, ((Number) out.get("candidates")).intValue());
        assertEquals("busca aleatória estruturada", out.get("method"));
    }
}