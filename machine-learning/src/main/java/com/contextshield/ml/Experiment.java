package com.contextshield.ml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Prepara os dados, treina, avalia, grava, recarrega e compara. Também roda a busca de pesos. */
public final class Experiment {
    private Experiment() {}

    public static Map<String, Object> run(int count, long seed, Path directory) throws IOException, ClassNotFoundException {
        return run(count, seed, directory, new double[0][], new int[0]);
    }

    public static Map<String, Object> run(int count, long seed, Path directory, double[][] confirmedX, int[] confirmedY)
            throws IOException, ClassNotFoundException {
        long started = System.nanoTime();
        List<SyntheticCases.Case> all = SyntheticCases.generate(count, seed);
        SyntheticCases.Parts parts = SyntheticCases.split(all, seed);
        writeDataset(directory.resolve("datasets").resolve("sintetico-semente-" + seed + ".csv"), parts);
        double[][] trainX = append(matrix(parts.train()), confirmedX);
        int[] trainY = append(labels(parts.train()), confirmedY);
        double[][] testX = matrix(parts.test());
        int[] testY = labels(parts.test());

        long t0 = System.nanoTime();
        SavedModel logisticSaved = Trainers.logistic(trainX, trainY);
        double logisticTrainMs = millis(t0);
        long t1 = System.nanoTime();
        SavedModel forestSaved = Trainers.forest(trainX, trainY);
        double forestTrainMs = millis(t1);

        Path models = directory.resolve("models");
        Path logisticPath = models.resolve("logistic-regression.ser");
        Path forestPath = models.resolve("random-forest.ser");
        logisticSaved.save(logisticPath);
        forestSaved.save(forestPath);
        SavedModel logisticLoaded = SavedModel.load(logisticPath);
        SavedModel forestLoaded = SavedModel.load(forestPath);

        Eval logistic = evaluate(logisticLoaded, testX, testY);
        Eval forest = evaluate(forestLoaded, testX, testY);
        boolean reload = same(logisticSaved, logisticLoaded, testX) && same(forestSaved, forestLoaded, testX);

        Map<String, Object> optimization = WeightSearch.run(parts.search(), parts.confirm(), seed, 36);
        List<SyntheticCases.Case> shift = SyntheticCases.generateShift(Math.max(200, count / 5), seed + 9176L);
        Eval shiftLogistic = evaluate(logisticLoaded, matrix(shift), labels(shift));
        Eval shiftForest = evaluate(forestLoaded, matrix(shift), labels(shift));
        String winner = winner(logistic.metrics, forest.metrics);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("executedAt", Instant.now().toString());
        report.put("library", Trainers.LIBRARY);
        report.put("libraryVersion", Trainers.LIBRARY_VERSION);
        report.put("java", Runtime.version().toString());
        report.put("origin", "sintético");
        report.put("limitation", SyntheticCases.LIMITATION);
        report.put("seed", seed);
        report.put("requestedExamples", count);
        report.put("generatedExamples", all.size());
        report.put("inconclusiveExcluded", parts.inconclusive());
        report.put("stealthExcluded", parts.stealth());
        report.put("modelFrauds", parts.modelFraud());
        report.put("modelLegits", parts.modelLegit());
        report.put("accountedExamples", parts.inconclusive() + parts.stealth() + parts.modelFraud() + parts.modelLegit());
        report.put("trainExamples", trainY.length);
        report.put("confirmedInTrain", confirmedY.length);
        report.put("testExamples", parts.test().size());
        report.put("trainClassBalance", balance(trainY));
        report.put("testClassBalance", balance(testY));
        report.put("features", List.of(Features.NAMES));
        report.put("logistic", modelReport("logistic-regression", Trainers.logisticHyperparameters(), logisticPath, logisticTrainMs, logistic, Acceptance.passes(logistic.metrics)));
        report.put("forest", modelReport("random-forest", Trainers.forestHyperparameters(Features.NAMES.length), forestPath, forestTrainMs, forest, Acceptance.passes(forest.metrics)));
        report.put("reloadMatchedTraining", reload);
        report.put("acceptance", Acceptance.criteria());
        report.put("winnerByF1ThenRecall", winner);
        report.put("winnerNote", "A escolha usa F1 e, no empate, o recall. Acurácia não decide. Dados sintéticos não provam detecção de fraude real.");
        report.put("holdoutChecks", holdout(parts.test(), logisticLoaded, forestLoaded));
        report.put("generalization", generalization(shift, shiftLogistic, shiftForest));
        report.put("optimization", optimization);
        report.put("totalSeconds", Math.round(millis(started) / 10.0) / 100.0);
        return report;
    }

    private static Map<String, Object> generalization(List<SyntheticCases.Case> shift, Eval logistic, Eval forest) {
        int fraud = 0;
        int legit = 0;
        for (SyntheticCases.Case item : shift) {
            if ("FRAUD".equals(item.label())) fraud++;
            else legit++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("note", "Mistura diferente da do treino. Estes exemplos não entram no ajuste do modelo nem na busca de pesos.");
        out.put("examples", shift.size());
        out.put("frauds", fraud);
        out.put("legits", legit);
        out.put("logistic", logistic.metrics);
        out.put("forest", forest.metrics);
        return out;
    }

    private static Map<String, Object> modelReport(String name, Map<String, Object> hyperparameters, Path path,
                                                   double trainMs, Eval eval, boolean accepted) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("algorithm", name);
        out.put("hyperparameters", hyperparameters);
        out.put("path", path.toAbsolutePath().toString());
        out.put("trainMs", Math.round(trainMs * 100.0) / 100.0);
        out.put("metrics", eval.metrics);
        out.put("validation", accepted ? "PASS" : "FAIL");
        out.put("approved", false);
        return out;
    }

    private static String winner(Map<String, Object> logistic, Map<String, Object> forest) {
        double f1L = num(logistic.get("f1"));
        double f1F = num(forest.get("f1"));
        if (Math.abs(f1L - f1F) < 0.01) {
            double recallL = num(logistic.get("recall"));
            double recallF = num(forest.get("recall"));
            if (Math.abs(recallL - recallF) < 0.01) return "empate prático: a diferença não sustenta uma conclusão";
            return recallF > recallL ? "random-forest" : "logistic-regression";
        }
        return f1F > f1L ? "random-forest" : "logistic-regression";
    }

    private static List<Map<String, Object>> holdout(List<SyntheticCases.Case> test, SavedModel logistic, SavedModel forest) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int shown = 0;
        for (SyntheticCases.Case item : test) {
            if (shown >= 8) break;
            double[] row = Features.vector(item.checkout());
            SavedModel.Prediction a = logistic.predict(row);
            SavedModel.Prediction b = forest.predict(row);
            rows.add(Map.of(
                    "story", item.story(),
                    "truth", item.label(),
                    "logistic", a.label(),
                    "logisticProbability", Math.round(a.fraudProbability() * 1000.0) / 1000.0,
                    "forest", b.label(),
                    "forestProbability", Math.round(b.fraudProbability() * 1000.0) / 1000.0));
            shown++;
        }
        return rows;
    }

    private static boolean same(SavedModel trained, SavedModel loaded, double[][] x) {
        int n = Math.min(25, x.length);
        for (int i = 0; i < n; i++) {
            if (!trained.predict(x[i]).label().equals(loaded.predict(x[i]).label())) return false;
        }
        return n > 0;
    }

    private record Eval(Map<String, Object> metrics) {}

    private static Eval evaluate(SavedModel model, double[][] x, int[] y) {
        int[] predicted = new int[y.length];
        double[] probability = new double[y.length];
        long t0 = System.nanoTime();
        for (int i = 0; i < x.length; i++) {
            SavedModel.Prediction prediction = model.predict(x[i]);
            predicted[i] = "FRAUD".equals(prediction.label()) ? 1 : 0;
            probability[i] = prediction.fraudProbability();
        }
        double meanMs = x.length == 0 ? 0 : (System.nanoTime() - t0) / 1_000_000.0 / x.length;
        return new Eval(Metrics.evaluate(y, predicted, probability, meanMs));
    }

    private static double[][] matrix(List<SyntheticCases.Case> cases) {
        double[][] x = new double[cases.size()][];
        for (int i = 0; i < cases.size(); i++) x[i] = Features.vector(cases.get(i).checkout());
        return x;
    }

    private static double[][] append(double[][] base, double[][] extra) {
        if (extra == null || extra.length == 0) return base;
        double[][] out = new double[base.length + extra.length][];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(extra, 0, out, base.length, extra.length);
        return out;
    }

    private static int[] append(int[] base, int[] extra) {
        if (extra == null || extra.length == 0) return base;
        int[] out = new int[base.length + extra.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(extra, 0, out, base.length, extra.length);
        return out;
    }

    private static int[] labels(List<SyntheticCases.Case> cases) {
        int[] y = new int[cases.size()];
        for (int i = 0; i < cases.size(); i++) y[i] = "FRAUD".equals(cases.get(i).label()) ? 1 : 0;
        return y;
    }

    private static Map<String, Integer> balance(int[] y) {
        int fraud = 0;
        for (int label : y) if (label == 1) fraud++;
        return Map.of("fraud", fraud, "legit", y.length - fraud);
    }

    private static void writeDataset(Path path, SyntheticCases.Parts parts) throws IOException {
        Files.createDirectories(path.getParent());
        StringBuilder csv = new StringBuilder("split,story,label");
        for (String name : Features.NAMES) csv.append(',').append(name);
        csv.append('\n');
        writeRows(csv, "train", parts.train());
        writeRows(csv, "search", parts.search());
        writeRows(csv, "confirm", parts.confirm());
        writeRows(csv, "test", parts.test());
        Files.writeString(path, csv.toString());
    }

    private static void writeRows(StringBuilder csv, String split, List<SyntheticCases.Case> cases) {
        for (SyntheticCases.Case item : cases) {
            if (!item.forModel() && !"train".equals(split) && !"test".equals(split)) {
                // a busca inclui casos que o modelo não vê; o arquivo marca o rótulo mesmo assim
            }
            double[] row = Features.vector(item.checkout());
            csv.append(split).append(',').append(item.story()).append(',').append(item.label());
            for (double value : row) csv.append(',').append(value);
            csv.append('\n');
        }
    }

    private static double millis(long start) {
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static double num(Object value) {
        return ((Number) value).doubleValue();
    }
}
