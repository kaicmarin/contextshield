package com.contextshield.risk.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Um fato observado na compra, com o peso que ele soma (ou tira) do score. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Signal(
        String id,
        String source,
        String label,
        String clientLabel,
        String detail,
        int basePoints,
        int points,
        String state,          // ok | attention | risk
        int confidence,
        boolean discounted,
        String dependsOn,
        String entityId,
        List<String> incidentIds
) {
    public Signal withPoints(int newPoints, boolean isDiscounted, String dep) {
        return new Signal(id, source, label, clientLabel, detail, basePoints, newPoints,
                stateFor(newPoints), confidence, isDiscounted, dep, entityId, incidentIds);
    }

    public Signal withBasePoints(int newBase) {
        return new Signal(id, source, label, clientLabel, detail, newBase, newBase,
                stateFor(newBase), confidence, false, null, entityId, incidentIds);
    }

    public static String stateFor(int points) {
        if (points <= 0) return "ok";
        return points >= 8 ? "risk" : "attention";
    }
}
