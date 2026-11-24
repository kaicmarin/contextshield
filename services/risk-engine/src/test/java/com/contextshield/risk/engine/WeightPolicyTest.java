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
    void blockThresholdCannotBeWeakenedPast80() {
        assertEquals(80, Weights.value(Map.of("THRESHOLD_BLOCK", 95), "THRESHOLD_BLOCK"));
        assertEquals(20, Weights.value(Map.of("DESTINATION_IN_INCIDENT", 4), "DESTINATION_IN_INCIDENT"));
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
