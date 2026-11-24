package com.contextshield.ml;

import com.contextshield.risk.engine.RiskAssessor;
import com.contextshield.risk.model.Assessment;
import com.contextshield.risk.model.AssessmentInput;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compara a avaliação só com a operação e a avaliação com as respostas já coletadas.
 * O conjunto reservado não escolhe hiperparâmetro nem modelo.
 */
public final class ContextStudy {
    private ContextStudy() {}

    public static void main(String[] args) throws Exception {
        int development = args.length > 0 ? Integer.parseInt(args[0]) : 800;
        long developmentSeed = args.length > 1 ? Long.parseLong(args[1]) : 8801L;
        int reserved = args.length > 2 ? Integer.parseInt(args[2]) : 400;
        long reservedSeed = args.length > 3 ? Long.parseLong(args[3]) : 9901L;
        Path root = Files.isDirectory(Path.of("src", "main", "java", "com", "contextshield", "ml"))
                ? Path.of(".") : Path.of("machine-learning");
        Map<String, Object> report = run(development, developmentSeed, reserved, reservedSeed, root);
        System.out.println(report.get("orderedByValidationF1"));
        System.out.println(report.get("falseNegativesOnReserved"));
    }

    public static Map<String, Object> run(int developmentCount, long developmentSeed, int reservedCount, long reservedSeed, Path directory)
            throws IOException, ClassNotFoundException {
        if (reservedSeed == developmentSeed) throw new IllegalArgumentException("A avaliação final precisa de outra semente.");
        List<ContextCases.Case> development = ContextCases.development(developmentCount, developmentSeed);
        Split split = split(development, developmentSeed);
        List<ContextCases.Case> reserved = ContextCases.reserved(reservedCount, reservedSeed);

        SavedModel stageOneLogistic = Trainers.logistic(matrix(split.train, false), labels(split.train));
        SavedModel stageOneForest = Trainers.forest(matrix(split.train, false), labels(split.train));
        SavedModel stageTwoLogistic = Trainers.logistic(matrix(split.train, true), labels(split.train),
                ContextFeatures.NAMES, "logistic-regression-context");
        SavedModel stageTwoForest = Trainers.forest(matrix(split.train, true), labels(split.train),
                ContextFeatures.NAMES, "random-forest-context");

        Path models = directory.resolve("models");
        Path logisticPath = models.resolve("logistic-context.ser");
        Path forestPath = models.resolve("random-forest-context.ser");
        stageTwoLogistic.save(logisticPath);
        stageTwoForest.save(forestPath);
        SavedModel logisticLoaded = SavedModel.load(logisticPath);
        SavedModel forestLoaded = SavedModel.load(forestPath);

        Map<String, Object> validation = compare(split.validation, stageOneLogistic, stageOneForest, stageTwoLogistic, stageTwoForest);
        Map<String, Object> test = compare(split.test, stageOneLogistic, stageOneForest, logisticLoaded, forestLoaded);
        Map<String, Object> reservedEval = compare(reserved, stageOneLogistic, stageOneForest, logisticLoaded, forestLoaded);
        String selected = select(validation);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("executedAt", Instant.now().toString());
        report.put("library", Trainers.LIBRARY + " " + Trainers.LIBRARY_VERSION);
        report.put("java", Runtime.version().toString());
        report.put("origin", "sintético");
        report.put("promoted", false);
        report.put("developmentSeed", developmentSeed);
        report.put("reservedSeed", reservedSeed);
        report.put("reservedUsedForSelection", false);
        report.put("developmentExamples", development.size());
        report.put("train", split.train.size());
        report.put("validation", split.validation.size());
        report.put("developmentTest", split.test.size());
        report.put("reservedExamples", reserved.size());
        report.put("operationFeatures", List.of(Features.NAMES));
        report.put("contextFeatures", ContextFeatures.SLOTS.stream().map(slot -> Map.of(
                "name", slot.name(), "provenance", slot.provenance(), "meaning", slot.meaning())).toList());
        report.put("diagnosis", diagnosis());
        report.put("separationOnTrain", separation(split.train));
        report.put("validation", validation);
        report.put("developmentTestMetrics", test);
        report.put("reservedEvaluation", reservedEval);
        report.put("orderedByValidationF1", selected);
        report.put("selectionNote", "A ordem usa só o F1 da validação do desenvolvimento. O conjunto reservado não altera modelo, corte nem hiperparâmetro.");
        report.put("engineOnReserved", engine(reserved));
        report.put("falseNegativesOnReserved", mistakes(reserved, logisticLoaded, forestLoaded));
        report.put("reloadMatched", same(stageTwoLogistic, logisticLoaded, matrix(split.test, true))
                && same(stageTwoForest, forestLoaded, matrix(split.test, true)));
        report.put("paths", Map.of("logistic", logisticPath.toAbsolutePath().toString(), "forest", forestPath.toAbsolutePath().toString()));
        report.put("limitation", "As respostas são sintéticas. Fraude que nega ajuda continua com o mesmo vetor de uma compra lícita. Isso não é evidência bancária.");
        Files.createDirectories(directory.resolve("reports"));
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(directory.resolve("reports").resolve("context-study.json").toFile(), report);
        return report;
    }

    private static Map<String, Object> diagnosis() {
        int same = 0;
        int count = 40;
        for (int i = 0; i < count; i++) {
            SyntheticCases.Case[] twin = SyntheticCases.hardFraudAndHabitualTwin(i, 77001L);
            if (Arrays.equals(Features.vector(twin[0].checkout()), Features.vector(twin[1].checkout()))) same++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pairs", count);
        out.put("identicalOperationVectors", same);
        out.put("leakage", "As respostas não entram nos 19 atributos da primeira avaliação.");
        out.put("finding", same == count
                ? "Os 19 atributos da fraude discreta são iguais aos da compra habitual gerada com a mesma semente. Nenhum algoritmo distingue esses pares sem contexto novo."
                : "Os pares não ficaram idênticos; a geração mudou.");
        return out;
    }

    private static Map<String, Object> separation(List<ContextCases.Case> train) {
        double[] fraud = new double[ContextFeatures.NAMES.length];
        double[] legit = new double[ContextFeatures.NAMES.length];
        int frauds = 0;
        int legits = 0;
        for (ContextCases.Case item : train) {
            double[] row = ContextFeatures.vector(withAnswers(item));
            double[] bucket = "FRAUD".equals(item.label()) ? fraud : legit;
            if ("FRAUD".equals(item.label())) frauds++;
            else legits++;
            for (int i = 0; i < row.length; i++) bucket[i] += row[i];
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < ContextFeatures.NAMES.length; i++) {
            double fraudMean = frauds == 0 ? 0 : fraud[i] / frauds;
            double legitMean = legits == 0 ? 0 : legit[i] / legits;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("feature", ContextFeatures.NAMES[i]);
            row.put("fraudMean", round(fraudMean));
            row.put("legitMean", round(legitMean));
            row.put("difference", round(fraudMean - legitMean));
            rows.add(row);
        }
        rows.sort((a, b) -> Double.compare(Math.abs((Double) b.get("difference")), Math.abs((Double) a.get("difference"))));
        return Map.of("trainFrauds", frauds, "trainLegits", legits, "largestDifferences", rows.subList(0, Math.min(8, rows.size())));
    }

    private static String select(Map<String, Object> validation) {
        double logistic = f1(validation, "contextLogistic");
        double forest = f1(validation, "contextForest");
        if (Math.abs(logistic - forest) < 0.01) return "empate prático na validação; nenhum dos dois é promovido";
        return forest > logistic ? "random-forest-context fica à frente na validação e não é promovido" : "logistic-regression-context fica à frente na validação e não é promovido";
    }

    @SuppressWarnings("unchecked")
    private static double f1(Map<String, Object> block, String key) {
        Object value = ((Map<String, Object>) block.get(key)).get("f1");
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static Map<String, Object> compare(List<ContextCases.Case> cases, SavedModel oneLogistic, SavedModel oneForest,
                                               SavedModel twoLogistic, SavedModel twoForest) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("examples", cases.size());
        out.put("operationLogistic", metrics(cases, oneLogistic, false));
        out.put("operationForest", metrics(cases, oneForest, false));
        out.put("contextLogistic", metrics(cases, twoLogistic, true));
        out.put("contextForest", metrics(cases, twoForest, true));
        return out;
    }

    private static Map<String, Object> metrics(List<ContextCases.Case> cases, SavedModel model, boolean context) {
        int[] actual = labels(cases);
        int[] predicted = new int[cases.size()];
        double[] probability = new double[cases.size()];
        long started = System.nanoTime();
        for (int i = 0; i < cases.size(); i++) {
            SavedModel.Prediction prediction = model.predict(context ? ContextFeatures.vector(withAnswers(cases.get(i))) : Features.vector(cases.get(i).checkout()));
            predicted[i] = "FRAUD".equals(prediction.label()) ? 1 : 0;
            probability[i] = prediction.fraudProbability();
        }
        double meanMs = cases.isEmpty() ? 0 : (System.nanoTime() - started) / 1_000_000.0 / cases.size();
        return Metrics.evaluate(actual, predicted, probability, meanMs);
    }

    private static Map<String, Object> mistakes(List<ContextCases.Case> cases, SavedModel logistic, SavedModel forest) {
        Map<String, Integer> logisticMiss = new LinkedHashMap<>();
        Map<String, Integer> forestMiss = new LinkedHashMap<>();
        for (ContextCases.Case item : cases) {
            if (!"FRAUD".equals(item.label())) continue;
            if ("LEGIT".equals(logistic.predict(ContextFeatures.vector(withAnswers(item))).label()))
                logisticMiss.merge(item.story(), 1, Integer::sum);
            if ("LEGIT".equals(forest.predict(ContextFeatures.vector(withAnswers(item))).label()))
                forestMiss.merge(item.story(), 1, Integer::sum);
        }
        return Map.of("contextLogistic", logisticMiss, "contextForest", forestMiss,
                "reading", "Fraude que nega ajuda não tem atributo diferente da compra lícita. Orientação declarada é outra história.");
    }

    private static Map<String, Object> engine(List<ContextCases.Case> cases) throws IOException {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        RiskAssessor assessor = new RiskAssessor();
        int fraudReleased = 0;
        int fraudHeld = 0;
        int legitHeld = 0;
        int legitReleased = 0;
        int fraudReleasedAfterAnswers = 0;
        int fraudHeldAfterAnswers = 0;
        for (ContextCases.Case item : cases) {
            AssessmentInput input = mapper.convertValue(item.checkout(), AssessmentInput.class);
            Assessment first = assessor.assess(input);
            String decision = first.decision();
            if ("REQUEST_CONTEXT".equals(decision) && item.answers() != null) {
                AssessmentInput.Answers answers = mapper.convertValue(item.answers(), AssessmentInput.Answers.class);
                decision = assessor.assess(input.withStage("recalculated", answers)).decision();
            }
            boolean released = "APPROVE".equals(decision) || "APPROVE_WITH_ALERT".equals(decision);
            if ("FRAUD".equals(item.label())) {
                if (released) fraudReleased++;
                else fraudHeld++;
            } else if (released) legitReleased++;
            else legitHeld++;
            if ("FRAUD".equals(item.label()) && item.answers() != null) {
                AssessmentInput.Answers answers = mapper.convertValue(item.answers(), AssessmentInput.Answers.class);
                String after = assessor.assess(input.withStage("recalculated", answers)).decision();
                boolean stillReleased = "APPROVE".equals(after) || "APPROVE_WITH_ALERT".equals(after);
                if (stillReleased) fraudReleasedAfterAnswers++;
                else fraudHeldAfterAnswers++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fraudReleased", fraudReleased);
        out.put("fraudHeld", fraudHeld);
        out.put("legitReleased", legitReleased);
        out.put("legitHeld", legitHeld);
        out.put("fraudReleasedWhenAnswersAreApplied", fraudReleasedAfterAnswers);
        out.put("fraudHeldWhenAnswersAreApplied", fraudHeldAfterAnswers);
        out.put("note", "A política padrão não pede verificação numa compra habitual. As respostas só entram se a verificação acontecer. A segunda contagem aplica as respostas só nesta comparação.");
        return out;
    }

    private static boolean same(SavedModel trained, SavedModel loaded, double[][] rows) {
        int n = Math.min(15, rows.length);
        for (int i = 0; i < n; i++) {
            if (!trained.predict(rows[i]).label().equals(loaded.predict(rows[i]).label())) return false;
        }
        return n > 0;
    }

    private static Map<String, Object> withAnswers(ContextCases.Case item) {
        Map<String, Object> checkout = new LinkedHashMap<>(item.checkout());
        if (item.answers() != null) checkout.put("answers", item.answers());
        else checkout.remove("answers");
        return checkout;
    }

    private static double[][] matrix(List<ContextCases.Case> cases, boolean context) {
        double[][] rows = new double[cases.size()][];
        for (int i = 0; i < cases.size(); i++) {
            rows[i] = context ? ContextFeatures.vector(withAnswers(cases.get(i))) : Features.vector(cases.get(i).checkout());
        }
        return rows;
    }

    private static int[] labels(List<ContextCases.Case> cases) {
        int[] labels = new int[cases.size()];
        for (int i = 0; i < cases.size(); i++) labels[i] = "FRAUD".equals(cases.get(i).label()) ? 1 : 0;
        return labels;
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private record Split(List<ContextCases.Case> train, List<ContextCases.Case> validation, List<ContextCases.Case> test) {}

    private static Split split(List<ContextCases.Case> cases, long seed) {
        List<ContextCases.Case> shuffled = new ArrayList<>(cases);
        java.util.Collections.shuffle(shuffled, new java.util.Random(seed ^ 0x5DEECE66L));
        int train = (int) Math.round(shuffled.size() * 0.60);
        int validation = (int) Math.round(shuffled.size() * 0.20);
        return new Split(
                List.copyOf(shuffled.subList(0, train)),
                List.copyOf(shuffled.subList(train, train + validation)),
                List.copyOf(shuffled.subList(train + validation, shuffled.size())));
    }
}
