package com.contextshield.risk.engine;

import com.contextshield.risk.model.AssessmentInput;
import com.contextshield.risk.model.DynamicRule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DynamicPolicyEquivalenceTest {

    @Test
    void dynamicCatalogMatchesLegacyAcrossSamples() {
        List<DynamicRule> rules = BuiltinRuleCatalog.publishedDefaults();
        var samples = List.of(
                sample(50, "initial"),
                sample(85, "initial"),
                sample(60, "initial"),
                sample(25, "initial"),
                sample(70, "recalculated"),
                sample(20, "recalculated"));
        for (var in : samples) {
            var cfg = in.configOrDefault();
            var cfgDyn = in.withConfig(new AssessmentInput.EngineConfig(cfg.flags(), cfg.providers(), cfg.weights(), cfg.contextProbes(), rules)).configOrDefault();
            Policy.Scored scored = Policy.score(List.of(), true, List.of(), "recalculated".equals(in.stage()), cfg.weights());
            Policy.Outcome legacy = Policy.legacyDecide(scored, in.stage(), cfg);
            Policy.Outcome dynamic = Policy.decide(scored, in.stage(), cfgDyn);
            assertEquals(legacy.decision(), dynamic.decision(), "decision " + in.stage() + " score " + scored.score());
            assertEquals(legacy.ruleId(), dynamic.ruleId());
        }
    }

    private static AssessmentInput sample(int amount, String stage) {
        var customer = new AssessmentInput.Customer("c1", "A", "SP", LocalDate.of(2020, 1, 1), 100, 5);
        var device = new AssessmentInput.Device("d1", "Phone", "", "SP", LocalDateTime.now().minusDays(30), true);
        var address = new AssessmentInput.Address("a1", "Casa", "Rua 1", "", "", "SP", "home", List.of("c1"));
        var merchant = new AssessmentInput.Merchant("m1", "Loja", false, true, LocalDate.of(2019, 1, 1));
        return new AssessmentInput(stage, LocalDateTime.of(2026, 6, 15, 15, 0), amount, customer, device, address, address,
                true, merchant, List.of(), "self", null, null, List.of(),
                AssessmentInput.EngineConfig.defaults(), List.of());
    }
}