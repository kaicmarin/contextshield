package com.contextshield.ml;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Precisão, recall, F1, matriz, PR-AUC e calibração. Acurácia é registrada e não decide o modelo. */
public final class Metrics {
    private Metrics() {}

    public static Map<String, Object> evaluate(int[] truth, int[] predicted, double[] fraudProbability, double inferenceMs) {
        int tp = 0, fp = 0, tn = 0, fn = 0;
        for (int i = 0; i < truth.length; i++) {
            if (truth[i] == 1 && predicted[i] == 1) tp++;
            else if (truth[i] == 0 && predicted[i] == 1) fp++;
            else if (truth[i] == 0 && predicted[i] == 0) tn++;
            else fn++;
        }
        int frauds = tp + fn;
        int legits = tn + fp;
        double precision = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
        double recall = frauds == 0 ? 0 : (double) tp / frauds;
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        double accuracy = truth.length == 0 ? 0 : (double) (tp + tn) / truth.length;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("examples", truth.length);
        out.put("frauds", frauds);
        out.put("legits", legits);
        out.put("precision", round(precision));
        out.put("recall", round(recall));
        out.put("f1", round(f1));
        out.put("accuracy", round(accuracy));
        out.put("falsePositives", fp);
        out.put("falseNegatives", fn);
        out.put("truePositives", tp);
        out.put("trueNegatives", tn);
        out.put("confusion", Map.of("legitLegit", tn, "legitFraud", fp, "fraudLegit", fn, "fraudFraud", tp));
        out.put("prAuc", round(averagePrecision(truth, fraudProbability)));
        out.put("calibrationError", round(calibration(truth, fraudProbability)));
        out.put("meanInferenceMs", round(inferenceMs));
        return out;
    }

    static double averagePrecision(int[] truth, double[] probability) {
        Integer[] order = new Integer[truth.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(probability[b], probability[a]));
        int hits = 0;
        double sum = 0;
        int positives = 0;
        for (int label : truth) if (label == 1) positives++;
        if (positives == 0) return 0;
        for (int rank = 0; rank < order.length; rank++) {
            if (truth[order[rank]] == 1) {
                hits++;
                sum += (double) hits / (rank + 1);
            }
        }
        return sum / positives;
    }

    /** Erro de calibração em 5 faixas: distância entre a probabilidade média e a fração real de fraudes. */
    static double calibration(int[] truth, double[] probability) {
        double[] sum = new double[5];
        int[] count = new int[5];
        int[] frauds = new int[5];
        for (int i = 0; i < truth.length; i++) {
            int bin = Math.min(4, (int) Math.floor(probability[i] * 5));
            if (bin < 0) bin = 0;
            sum[bin] += probability[i];
            count[bin]++;
            frauds[bin] += truth[i];
        }
        double error = 0;
        int used = 0;
        for (int bin = 0; bin < 5; bin++) {
            if (count[bin] == 0) continue;
            double predicted = sum[bin] / count[bin];
            double observed = (double) frauds[bin] / count[bin];
            error += count[bin] * Math.abs(predicted - observed);
            used += count[bin];
        }
        return used == 0 ? 0 : error / used;
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
