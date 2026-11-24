package com.contextshield.risk.engine;

import com.contextshield.risk.model.AssessmentInput;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightPolicyTest {

    @Test
    void emptyPolicyMatchesTheOriginalPoints() {
        var base = new RiskAssessor().assess(sample(Map.of()));
        var same = new RiskAssessor().assess(sample(null));
        assertEquals(base.score(), same.score());
        assertEquals(base.decision(), same.decision());
        assertEquals(base.ruleId(), same.ruleId());
        assertTrue(base.signals().stream().anyMatch(s -> s.id().equals("NEW_DEVICE") && s.basePoints() == 8));
    }

    @Test
    void aHigherDeviceWeightChangesTheScoreWithoutChangingTheRuleOrder() {
        var base = new RiskAssessor().assess(sample(Map.of()));
        var heavier = new RiskAssessor().assess(sample(Map.of("NEW_DEVICE", 16)));
        assertEquals(base.score() + 8, heavier.score());
        assertEquals("R7", base.ruleId());
        assertEquals("R5", heavier.ruleId());
    }

    @Test
    void unknownContextDoesNotInterruptAHabitualPurchase() {
        var afternoon = habitual(java.time.LocalDateTime.of(2026, 6, 15, 15, 0));
        var off = new RiskAssessor().assess(afternoon);
        var on = new RiskAssessor().assess(afternoon.withConfig(flagOn(Map.of())));
        assertEquals("R8", off.ruleId());
        assertEquals("R8", on.ruleId());
        assertEquals("APPROVE", on.decision());
        assertEquals(off.score(), on.score());
    }

    @Test
    void unknownContextAsksWhenAnExistingAttentionSignalIsPresent() {
        var night = habitual(java.time.LocalDateTime.of(2026, 6, 15, 2, 0));
        var off = new RiskAssessor().assess(night);
        var on = new RiskAssessor().assess(night.withConfig(flagOn(Map.of())));
        var silenced = new RiskAssessor().assess(night.withConfig(flagOn(Map.of(), java.util.List.of("NONE"))));
        assertEquals("R8", off.ruleId());
        assertTrue(off.signals().stream().anyMatch(s -> s.id().equals("UNUSUAL_HOUR")));
        assertEquals(off.score(), on.score());
        assertEquals("R12", on.ruleId());
        assertEquals("REQUEST_CONTEXT", on.decision());
        assertTrue(on.verification().questions().stream().anyMatch(q -> q.prompt().contains("Alguém pediu")));
        assertEquals("R8", silenced.ruleId());
        assertEquals(off.score(), silenced.score());
    }

    @Test
    void declaredDenialIsNotProofOfLegitimacy() {
        var afternoon = habitual(LocalDateTime.of(2026, 6, 15, 15, 0));
        var before = new RiskAssessor().assess(afternoon);
        var denied = new RiskAssessor().assess(afternoon.withStage("recalculated",
                new AssessmentInput.Answers("self", "no", "mine", "no", null, null, null)));
        assertTrue(denied.signals().stream().anyMatch(s -> s.id().equals("VER_CLEAR") && s.basePoints() < 0));
        assertTrue(denied.score() < before.score());
        assertTrue("APPROVE".equals(denied.decision()) || "APPROVE_WITH_ALERT".equals(denied.decision()));
        assertTrue("R8".equals(denied.ruleId()) || "S-R8".equals(denied.ruleId()));
        var coached = new RiskAssessor().assess(afternoon.withStage("recalculated",
                new AssessmentInput.Answers("asked", "phone", "mine", "yes", null, null, null)));
        assertEquals("R2", coached.ruleId());
        assertEquals("INTERVENE", coached.decision());
    }

    @Test
    void blockThresholdCannotBeWeakenedPast80() {
        assertEquals(80, Weights.value(Map.of("THRESHOLD_BLOCK", 95), "THRESHOLD_BLOCK"));
        assertEquals(20, Weights.value(Map.of("DESTINATION_IN_INCIDENT", 4), "DESTINATION_IN_INCIDENT"));
    }

    private static AssessmentInput.EngineConfig flagOn(Map<String, Integer> weights) {
        return flagOn(weights, java.util.List.of());
    }

    private static AssessmentInput.EngineConfig flagOn(Map<String, Integer> weights, java.util.List<String> probes) {
        var flags = new java.util.LinkedHashMap<>(AssessmentInput.EngineConfig.defaults().flags());
        flags.put("context-when-unknown", true);
        return new AssessmentInput.EngineConfig(flags, AssessmentInput.EngineConfig.defaults().providers(), weights, probes);
    }

    private static AssessmentInput habitual(LocalDateTime at) {
        return new AssessmentInput("initial", at, 120,
                new AssessmentInput.Customer("C1", "Ana", "Campinas", LocalDate.of(2021, 1, 1), 200, 12),
                new AssessmentInput.Device("D1", "Conhecido", "browser", "Campinas, SP", LocalDateTime.of(2024, 1, 1, 10, 0), true),
                new AssessmentInput.Address("A1", "Casa", "Rua das Flores, 10", null, "Centro", "Campinas", "home", List.of("C1")),
                null, true,
                new AssessmentInput.Merchant("M1", "NEXA", true, true, LocalDate.of(2018, 1, 1)),
                List.of(new AssessmentInput.Seller("S1", "Loja", LocalDate.of(2019, 1, 1), 0.4, 4.8, "ouro", 100)),
                "self", null, null, List.of(),
                new AssessmentInput.EngineConfig(AssessmentInput.EngineConfig.defaults().flags(), AssessmentInput.EngineConfig.defaults().providers(), Map.of()),
                List.of());
    }

    private static AssessmentInput sample(Map<String, Integer> weights) {
        var config = new AssessmentInput.EngineConfig(
                AssessmentInput.EngineConfig.defaults().flags(),
                AssessmentInput.EngineConfig.defaults().providers(),
                weights);
        return new AssessmentInput("initial", LocalDateTime.of(2026, 6, 15, 15, 0), 900,
                new AssessmentInput.Customer("C1", "Ana", "Campinas", LocalDate.of(2024, 1, 1), 100, 8),
                new AssessmentInput.Device("D1", "Novo", "browser", "Outra cidade", LocalDateTime.of(2026, 6, 15, 14, 57), false),
                new AssessmentInput.Address("A1", "Outro", "Rua do Comércio, 400", null, "Centro", "Campinas", "other", List.of()),
                null, false,
                new AssessmentInput.Merchant("M1", "NEXA", true, true, LocalDate.of(2018, 1, 1)),
                List.of(new AssessmentInput.Seller("S1", "Loja", LocalDate.of(2019, 1, 1), 0.4, 4.8, "ouro", 100)),
                "self", null, null, List.of(), config, List.of());
    }
}
