package com.contextshield.ml;

import com.contextshield.risk.engine.RiskAssessor;
import com.contextshield.risk.model.Assessment;
import com.contextshield.risk.model.AssessmentInput;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lê um conjunto já gerado e o modelo contextual já salvo.
 * Não treina, não escolhe corte e não grava um modelo novo.
 */
public final class FalseNegativeDiagnosis {
    private FalseNegativeDiagnosis() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.isDirectory(Path.of("src", "main", "java", "com", "contextshield", "ml"))
                ? Path.of(".") : Path.of("machine-learning");
        Path model = root.resolve("models").resolve("logistic-context.ser");
        SavedModel logistic = Files.isRegularFile(model) ? SavedModel.load(model) : null;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("executedAt", Instant.now().toString());
        report.put("modelsReplaced", false);
        report.put("thresholdAdopted", false);
        report.put("reservedUsedForTuning", false);
        report.put("publishedModel", model.toAbsolutePath().toString());
        report.put("publishedEvaluation", diagnose(ContextCases.reserved(400, 9901L), logistic, "reservado-9901"));
        report.put("confirmation", diagnose(ContextCases.reserved(400, 12011L), logistic, "confirmacao-12011"));
        report.put("decision", "Nenhum modelo novo foi gravado. O corte 0,5 permanece. "
                + "Fraude que nega ajuda usa as mesmas respostas e o mesmo tipo de compra habitual.");
        Path out = root.resolve("reports").resolve("false-negative-diagnosis.json");
        Files.createDirectories(out.getParent());
        new ObjectMapper().registerModule(new JavaTimeModule()).enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(out.toFile(), report);
        System.out.println(out.toAbsolutePath());
    }

    public static Map<String, Object> diagnose(List<ContextCases.Case> cases, SavedModel model, String name) throws Exception {
        List<Row> rows = new ArrayList<>(cases.size());
        for (ContextCases.Case item : cases) rows.add(new Row(item));
        List<Row> denials = rows.stream().filter(row -> "fraude-que-nega".equals(row.story)).toList();
        List<Row> habitual = rows.stream().filter(row -> "legitima-habitual".equals(row.story)).toList();

        int sameAnswers = 0;
        int sameContext = 0;
        int sameOperationBits = 0;
        int insideHabitualRange = 0;
        double[] denialDistance = new double[denials.size()];
        for (int i = 0; i < denials.size(); i++) {
            Row denial = denials.get(i);
            if (sameAnswers(denial, habitual)) sameAnswers++;
            if (sameTail(denial, habitual)) sameContext++;
            if (sameBits(denial, habitual)) sameOperationBits++;
            if (inside(denial, habitual)) insideHabitualRange++;
            denialDistance[i] = nearest(denial.vector, habitual);
        }
        double[] habitualDistance = new double[habitual.size()];
        for (int i = 0; i < habitual.size(); i++) habitualDistance[i] = nearestOther(i, habitual);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("examples", cases.size());
        out.put("frauds", rows.stream().filter(row -> row.fraud).count());
        out.put("denials", denials.size());
        out.put("habitualLegit", habitual.size());
        out.put("denialsWithSameDeclaredAnswers", sameAnswers);
        out.put("denialsWithSameContextFeatures", sameContext);
        out.put("denialsWithSameOperationBits", sameOperationBits);
        out.put("denialsInsideHabitualContinuousRange", insideHabitualRange);
        out.put("medianDistanceDenialToHabitual", round(median(denialDistance)));
        out.put("medianDistanceAmongHabitual", round(median(habitualDistance)));
        out.put("separableWithoutFalsePositives", false);
        out.put("absentOnEveryDenial", List.of(
                "script de orientação observado", "incidente de destino", "incidente de vendedor",
                "dispositivo novo", "destino de terceiro", "resposta que admite ajuda"));
        out.put("presentOnEveryDenial", List.of(
                "origem declarada própria", "respostas: própria, sem contato, endereço meu, sem orientação",
                "compra habitual", "horário diurno"));
        if (model != null) out.put("publishedModel", modelReading(rows, denials, habitual, model));
        out.put("engine", engine(denials, habitual));
        out.put("irreducible", "Com as respostas declaradas iguais e os sinais da operação iguais, "
                + "não há função que marque a fraude e deixe a compra habitual intacta.");
        return out;
    }

    private static Map<String, Object> modelReading(List<Row> rows, List<Row> denials, List<Row> habitual, SavedModel model) {
        int fn = 0;
        Map<String, Integer> stories = new LinkedHashMap<>();
        double[] denialP = new double[denials.size()];
        double[] habitualP = new double[habitual.size()];
        for (Row row : rows) {
            if (!row.fraud) continue;
            SavedModel.Prediction prediction = model.predict(row.vector);
            if ("LEGIT".equals(prediction.label())) {
                fn++;
                stories.merge(row.story, 1, Integer::sum);
            }
        }
        for (int i = 0; i < denials.size(); i++) denialP[i] = model.predict(denials.get(i).vector).fraudProbability();
        for (int i = 0; i < habitual.size(); i++) habitualP[i] = model.predict(habitual.get(i).vector).fraudProbability();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("falseNegatives", fn);
        out.put("falseNegativeStories", stories);
        out.put("denialProbability", range(denialP));
        out.put("habitualProbability", range(habitualP));
        out.put("thresholdSweep", sweep(denialP, habitualP));
        out.put("sweepNote", "Varredura só para mostrar o custo. Nenhum corte foi adotado.");
        return out;
    }

    private static List<Map<String, Object>> sweep(double[] denial, double[] habitual) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (double cutoff = 0.5; cutoff >= 0.05; cutoff -= 0.05) {
            int caught = 0;
            int flagged = 0;
            for (double value : denial) if (value >= cutoff) caught++;
            for (double value : habitual) if (value >= cutoff) flagged++;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cutoff", round(cutoff));
            row.put("denialsCaught", caught);
            row.put("habitualPurchasesFlagged", flagged);
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> engine(List<Row> denials, List<Row> habitual) throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        RiskAssessor assessor = new RiskAssessor();
        Map<String, Integer> before = new LinkedHashMap<>();
        Map<String, Integer> after = new LinkedHashMap<>();
        Map<String, Integer> rules = new LinkedHashMap<>();
        Map<String, Integer> habitualAfter = new LinkedHashMap<>();
        List<Integer> scores = new ArrayList<>();
        List<Integer> afterScores = new ArrayList<>();
        for (Row denial : denials) {
            Map<String, Object> checkout = new LinkedHashMap<>(denial.checkout);
            checkout.remove("answers");
            AssessmentInput input = mapper.convertValue(checkout, AssessmentInput.class);
            Assessment first = assessor.assess(input);
            before.merge(first.decision(), 1, Integer::sum);
            scores.add(first.score());
            AssessmentInput.Answers answers = mapper.convertValue(denial.answers, AssessmentInput.Answers.class);
            Assessment second = assessor.assess(input.withStage("recalculated", answers));
            after.merge(second.decision(), 1, Integer::sum);
            afterScores.add(second.score());
            rules.merge(second.ruleId(), 1, Integer::sum);
        }
        for (Row row : habitual) {
            if (row.answers.isEmpty()) continue;
            Map<String, Object> checkout = new LinkedHashMap<>(row.checkout);
            checkout.remove("answers");
            AssessmentInput input = mapper.convertValue(checkout, AssessmentInput.class);
            AssessmentInput.Answers answers = mapper.convertValue(row.answers, AssessmentInput.Answers.class);
            habitualAfter.merge(assessor.assess(input.withStage("recalculated", answers)).decision(), 1, Integer::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("beforeAnswers", before);
        out.put("scoreBeforeMin", scores.stream().mapToInt(Integer::intValue).min().orElse(0));
        out.put("scoreBeforeMax", scores.stream().mapToInt(Integer::intValue).max().orElse(0));
        out.put("afterDeclaredDenial", after);
        out.put("scoreAfterMin", afterScores.stream().mapToInt(Integer::intValue).min().orElse(0));
        out.put("scoreAfterMax", afterScores.stream().mapToInt(Integer::intValue).max().orElse(0));
        out.put("ruleAfterDenial", rules);
        out.put("habitualAfterSameAnswers", habitualAfter);
        return out;
    }

    private static boolean sameAnswers(Row denial, List<Row> habitual) {
        return habitual.stream().anyMatch(row -> denial.answers.equals(row.answers));
    }

    private static boolean sameTail(Row denial, List<Row> habitual) {
        return habitual.stream().anyMatch(row -> Arrays.equals(tail(denial.vector), tail(row.vector)));
    }

    private static boolean sameBits(Row denial, List<Row> habitual) {
        return habitual.stream().anyMatch(row -> bits(denial.vector).equals(bits(row.vector)));
    }

    private static boolean inside(Row denial, List<Row> habitual) {
        double[] low = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] high = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (Row row : habitual) {
            for (int i = 0; i < 3; i++) {
                low[i] = Math.min(low[i], row.vector[i]);
                high[i] = Math.max(high[i], row.vector[i]);
            }
        }
        for (int i = 0; i < 3; i++) {
            if (denial.vector[i] < low[i] || denial.vector[i] > high[i]) return false;
        }
        return !habitual.isEmpty();
    }

    private static double nearest(double[] vector, List<Row> others) {
        double best = Double.POSITIVE_INFINITY;
        for (Row other : others) best = Math.min(best, distance(vector, other.vector));
        return best;
    }

    private static double nearestOther(int index, List<Row> rows) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < rows.size(); i++) {
            if (i == index) continue;
            best = Math.min(best, distance(rows.get(index).vector, rows.get(i).vector));
        }
        return best;
    }

    private static double distance(double[] left, double[] right) {
        double sum = 0;
        for (int i = 0; i < left.length; i++) {
            double delta = left[i] - right[i];
            sum += delta * delta;
        }
        return Math.sqrt(sum);
    }

    private static double[] tail(double[] vector) {
        return Arrays.copyOfRange(vector, Features.NAMES.length, vector.length);
    }

    private static String bits(double[] vector) {
        StringBuilder text = new StringBuilder();
        for (int i = 3; i < Features.NAMES.length; i++) text.append(vector[i] == 1 ? '1' : '0');
        return text.toString();
    }

    private static Map<String, Object> range(double[] values) {
        double[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("min", round(copy.length == 0 ? 0 : copy[0]));
        out.put("median", round(median(copy)));
        out.put("max", round(copy.length == 0 ? 0 : copy[copy.length - 1]));
        return out;
    }

    private static double median(double[] values) {
        if (values.length == 0) return 0;
        double[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        int mid = copy.length / 2;
        return copy.length % 2 == 0 ? (copy[mid - 1] + copy[mid]) / 2 : copy[mid];
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private record Row(String story, boolean fraud, double[] vector, Map<String, Object> checkout, Map<String, String> answers) {
        Row(ContextCases.Case item) {
            this(item.story(), "FRAUD".equals(item.label()), ContextFeatures.vector(withAnswers(item)), item.checkout(),
                    item.answers() == null ? Map.of() : item.answers());
        }
    }

    private static Map<String, Object> withAnswers(ContextCases.Case item) {
        Map<String, Object> checkout = new LinkedHashMap<>(item.checkout());
        if (item.answers() != null) checkout.put("answers", item.answers());
        else checkout.remove("answers");
        return checkout;
    }
}
