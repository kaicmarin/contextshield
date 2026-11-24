package com.contextshield.ml;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FalseNegativeDiagnosisTest {

    @Test
    void reservedDenialsMatchHabitualPurchases() throws Exception {
        Map<String, Object> report = FalseNegativeDiagnosis.diagnose(ContextCases.reserved(400, 9901L), model(), "reservado-9901");
        long denials = ((Number) report.get("denials")).longValue();
        assertTrue(denials > 0);
        assertEquals(denials, ((Number) report.get("denialsWithSameDeclaredAnswers")).longValue());
        assertEquals(denials, ((Number) report.get("denialsWithSameContextFeatures")).longValue());
        assertEquals(denials, ((Number) report.get("denialsWithSameOperationBits")).longValue());
        assertEquals(false, report.get("separableWithoutFalsePositives"));
        assertTrue(((Number) report.get("medianDistanceDenialToHabitual")).doubleValue()
                <= ((Number) report.get("medianDistanceAmongHabitual")).doubleValue() + 0.05);
    }

    @Test
    void anotherSeedHasTheSameOverlap() throws Exception {
        Map<String, Object> report = FalseNegativeDiagnosis.diagnose(ContextCases.reserved(400, 12011L), null, "confirmacao-12011");
        long denials = ((Number) report.get("denials")).longValue();
        assertEquals(denials, ((Number) report.get("denialsWithSameContextFeatures")).longValue());
        assertFalse(report.containsKey("publishedModel"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishedModelMissesOnlyDenialsAndALowerCutoffFlagsHabitualPurchases() throws Exception {
        SavedModel model = model();
        if (model == null) return;
        Map<String, Object> report = FalseNegativeDiagnosis.diagnose(ContextCases.reserved(400, 9901L), model, "reservado-9901");
        Map<String, Object> reading = (Map<String, Object>) report.get("publishedModel");
        assertEquals(51, ((Number) reading.get("falseNegatives")).intValue());
        assertEquals(Map.of("fraude-que-nega", 51), reading.get("falseNegativeStories"));
        List<Map<String, Object>> sweep = (List<Map<String, Object>>) reading.get("thresholdSweep");
        Map<String, Object> current = sweep.get(0);
        assertEquals(0.5, ((Number) current.get("cutoff")).doubleValue());
        assertEquals(0, ((Number) current.get("denialsCaught")).intValue());
        assertEquals(0, ((Number) current.get("habitualPurchasesFlagged")).intValue());
        boolean anyLowerCatchWithoutFlag = sweep.stream().skip(1).anyMatch(row ->
                ((Number) row.get("denialsCaught")).intValue() > 0
                        && ((Number) row.get("habitualPurchasesFlagged")).intValue() == 0);
        assertFalse(anyLowerCatchWithoutFlag);
    }

    private static SavedModel model() throws Exception {
        Path root = Files.isDirectory(Path.of("src", "main", "java", "com", "contextshield", "ml"))
                ? Path.of(".") : Path.of("machine-learning");
        Path file = root.resolve("models").resolve("logistic-context.ser");
        return Files.isRegularFile(file) ? SavedModel.load(file) : null;
    }
}
