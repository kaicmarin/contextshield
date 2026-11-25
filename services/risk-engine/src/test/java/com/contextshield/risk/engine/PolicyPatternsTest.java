package com.contextshield.risk.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PolicyPatternsTest {

    @Test
    void eachCommercePatternChangesWeightsInsideTheAllowedRange() {
        assertEquals(3, PolicyPatterns.ALL.size());
        for (var pattern : PolicyPatterns.ALL) {
            assertFalse(pattern.summary().isBlank());
            assertFalse(pattern.weights().isEmpty());
            Weights.merge(pattern.weights());
        }
        assertEquals(12, PolicyPatterns.ALL.get(0).weights().get("RECENT_SELLER"));
        assertEquals(3, PolicyPatterns.ALL.get(1).weights().get("RECENT_SELLER"));
        assertEquals(14, PolicyPatterns.ALL.get(2).weights().get("ORIGIN_GUIDED"));
    }
}
