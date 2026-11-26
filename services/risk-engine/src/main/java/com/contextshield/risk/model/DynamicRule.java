package com.contextshield.risk.model;

import java.util.Map;

/** Regra publicada vinda da core-api (banco). O motor só interpreta, não persiste. */
public record DynamicRule(
        String key,
        String displayKey,
        String name,
        String summary,
        int priority,
        String kind,
        Map<String, Object> params,
        String outcome
) {
    public String ruleId() {
        return displayKey == null || displayKey.isBlank() ? key : displayKey;
    }
}