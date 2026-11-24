package com.contextshield.ml;

import smile.classification.LogisticRegression;
import smile.classification.RandomForest;
import smile.data.type.StructType;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Modelo da Smile já treinado, com o catálogo de características usado no treino. */
public final class SavedModel implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String algorithm;
    private final String libraryVersion;
    private final String[] featureNames;
    private final LogisticRegression logistic;
    private final RandomForest forest;
    private final StructType schema;

    public SavedModel(String algorithm, String libraryVersion, String[] featureNames,
                      LogisticRegression logistic, RandomForest forest, StructType schema) {
        this.algorithm = algorithm;
        this.libraryVersion = libraryVersion;
        this.featureNames = featureNames.clone();
        this.logistic = logistic;
        this.forest = forest;
        this.schema = schema;
    }

    public String algorithm() { return algorithm; }

    public String libraryVersion() { return libraryVersion; }

    public String[] featureNames() { return featureNames.clone(); }

    public Prediction predict(double[] features) {
        if (features == null || features.length != featureNames.length) {
            throw new IllegalArgumentException("O vetor tem " + (features == null ? 0 : features.length)
                    + " posições; o modelo espera " + featureNames.length + ".");
        }
        for (double value : features) {
            if (Double.isNaN(value) || Double.isInfinite(value)) throw new IllegalArgumentException("Característica ausente ou inválida.");
        }
        double[] posteriori = new double[2];
        int label = logistic != null
                ? logistic.predict(features, posteriori)
                : forest.predict(Trainers.tuple(schema, features), posteriori);
        int[] classes = logistic != null ? logistic.classes() : forest.classes();
        int fraudIndex = 0;
        for (int i = 0; i < classes.length; i++) if (classes[i] == 1) fraudIndex = i;
        double fraud = fraudIndex < posteriori.length ? posteriori[fraudIndex] : (label == 1 ? 1 : 0);
        return new Prediction(label == 1 ? "FRAUD" : "LEGIT", fraud, algorithm);
    }

    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(path))) {
            out.writeObject(this);
        }
    }

    public static SavedModel load(Path path) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(path))) {
            Object value = in.readObject();
            if (!(value instanceof SavedModel model)) throw new IOException("Arquivo não é um modelo do ContextShield.");
            boolean known = Arrays.equals(model.featureNames, Features.NAMES)
                    || Arrays.equals(model.featureNames, ContextFeatures.NAMES);
            if (!known) throw new IOException("Catálogo de características incompatível.");
            return model;
        }
    }

    public record Prediction(String label, double fraudProbability, String algorithm) {}
}
