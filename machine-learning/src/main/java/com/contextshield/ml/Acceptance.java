package com.contextshield.ml;

import java.util.Map;

/**
 * Critérios definidos antes de olhar o resultado. Valem para este conjunto sintético.
 * Passar aqui não autoriza publicação: a aprovação continua humana.
 */
public final class Acceptance {
    public static final int MIN_FRAUDS = 30;
    public static final int MIN_LEGITS = 30;
    public static final double MIN_PRECISION = 0.70;
    public static final double MIN_RECALL = 0.70;
    public static final double MIN_F1 = 0.70;
    public static final double MAX_FALSE_NEGATIVE_RATE = 0.30;
    public static final double MAX_MEAN_INFERENCE_MS = 5.0;

    private Acceptance() {}

    public static Map<String, Object> criteria() {
        return Map.of(
                "minFrauds", MIN_FRAUDS,
                "minLegits", MIN_LEGITS,
                "minPrecision", MIN_PRECISION,
                "minRecall", MIN_RECALL,
                "minF1", MIN_F1,
                "maxFalseNegativeRate", MAX_FALSE_NEGATIVE_RATE,
                "maxMeanInferenceMs", MAX_MEAN_INFERENCE_MS);
    }

    @SuppressWarnings("unchecked")
    public static boolean passes(Map<String, Object> metrics) {
        int frauds = ((Number) metrics.get("frauds")).intValue();
        int legits = ((Number) metrics.get("legits")).intValue();
        if (frauds < MIN_FRAUDS || legits < MIN_LEGITS) return false;
        double precision = ((Number) metrics.get("precision")).doubleValue();
        double recall = ((Number) metrics.get("recall")).doubleValue();
        double f1 = ((Number) metrics.get("f1")).doubleValue();
        double ms = ((Number) metrics.get("meanInferenceMs")).doubleValue();
        double fnRate = frauds == 0 ? 1 : ((Number) metrics.get("falseNegatives")).doubleValue() / frauds;
        return precision >= MIN_PRECISION && recall >= MIN_RECALL && f1 >= MIN_F1
                && fnRate <= MAX_FALSE_NEGATIVE_RATE && ms <= MAX_MEAN_INFERENCE_MS;
    }
}
