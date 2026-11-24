package com.contextshield.ml;

import smile.base.cart.SplitRule;
import smile.classification.LogisticRegression;
import smile.classification.RandomForest;
import smile.data.DataFrame;
import smile.data.Tuple;
import smile.data.formula.Formula;
import smile.data.type.StructType;
import smile.data.vector.IntVector;

import java.util.Map;

/** Treino real com a Smile. Os hiperparâmetros ficam fixos para o experimento poder ser repetido. */
public final class Trainers {
    public static final String LIBRARY = "smile-core";
    public static final String LIBRARY_VERSION = "3.1.1";
    public static final double LOGISTIC_LAMBDA = 0.1;
    public static final double LOGISTIC_TOLERANCE = 1e-5;
    public static final int LOGISTIC_ITERATIONS = 200;
    public static final int FOREST_TREES = 80;
    public static final int FOREST_DEPTH = 12;
    public static final int FOREST_NODE = 5;

    private Trainers() {}

    public static SavedModel logistic(double[][] x, int[] y) {
        return logistic(x, y, Features.NAMES, "logistic-regression");
    }

    public static SavedModel logistic(double[][] x, int[] y, String[] names, String algorithm) {
        LogisticRegression model = LogisticRegression.fit(x, y, LOGISTIC_LAMBDA, LOGISTIC_TOLERANCE, LOGISTIC_ITERATIONS);
        return new SavedModel(algorithm, LIBRARY_VERSION, names, model, null, null);
    }

    public static SavedModel forest(double[][] x, int[] y) {
        return forest(x, y, Features.NAMES, "random-forest");
    }

    public static SavedModel forest(double[][] x, int[] y, String[] names, String algorithm) {
        DataFrame frame = DataFrame.of(x, names).merge(IntVector.of("fraud", y));
        int mtry = Math.max(1, (int) Math.sqrt(x[0].length));
        RandomForest model = RandomForest.fit(Formula.lhs("fraud"), frame, FOREST_TREES, mtry, SplitRule.GINI,
                FOREST_DEPTH, 256, FOREST_NODE, 1.0, classWeight(y));
        StructType schema = DataFrame.of(x, names).schema();
        return new SavedModel(algorithm, LIBRARY_VERSION, names, null, model, schema);
    }

    static Tuple tuple(StructType schema, double[] features) {
        return Tuple.of(features, schema);
    }

    /** Peso inversamente proporcional à frequência, para a classe fraudulenta não sumir na votação. */
    static int[] classWeight(int[] y) {
        int legit = 0;
        int fraud = 0;
        for (int label : y) {
            if (label == 1) fraud++;
            else legit++;
        }
        int fraudWeight = Math.max(1, (int) Math.round((double) legit / Math.max(1, fraud)));
        return new int[] {1, fraudWeight};
    }

    public static Map<String, Object> logisticHyperparameters() {
        return Map.of("lambda", LOGISTIC_LAMBDA, "tolerance", LOGISTIC_TOLERANCE, "maxIterations", LOGISTIC_ITERATIONS);
    }

    public static Map<String, Object> forestHyperparameters(int features) {
        return Map.of(
                "trees", FOREST_TREES,
                "mtry", Math.max(1, (int) Math.sqrt(features)),
                "split", "GINI",
                "maxDepth", FOREST_DEPTH,
                "minNode", FOREST_NODE,
                "sampling", 1.0,
                "classWeight", "inverso da frequência");
    }
}
