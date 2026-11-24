package com.contextshield.ml;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextStudyTest {

    @Test
    void hardFraudUsesTheSameOperationVectorAsAHabitualPurchase() {
        SyntheticCases.Case[] twin = SyntheticCases.hardFraudAndHabitualTwin(3, 77001L);
        assertTrue(java.util.Arrays.equals(Features.vector(twin[0].checkout()), Features.vector(twin[1].checkout())));
        Map<String, Object> fraud = new java.util.LinkedHashMap<>(twin[0].checkout());
        fraud.put("answers", twin[0].answers());
        double[] context = ContextFeatures.vector(fraud);
        assertEquals(0, context[Features.NAMES.length + 4], 0);
        assertEquals(1, context[Features.NAMES.length + 3], 0);
    }

    @Test
    void missingAnswersStayUnknown() {
        ContextCases.Case item = ContextCases.development(80, 3L).stream()
                .filter(row -> row.answers() == null).findFirst().orElseThrow();
        double[] row = ContextFeatures.vector(item.checkout());
        assertEquals(1, row[Features.NAMES.length + 4], 0);
        assertEquals(0, row[Features.NAMES.length], 0);
        assertEquals("DESCONHECIDA", ContextFeatures.provenance(item.checkout()).get("declaredAsked"));
    }

    @Test
    void secondStageTrainsWithoutUsingTheReservedSet() throws Exception {
        Path dir = Files.createTempDirectory("cs-context");
        Map<String, Object> report = ContextStudy.run(160, 8801L, 80, 9901L, dir);
        assertEquals(false, report.get("promoted"));
        assertEquals(false, report.get("reservedUsedForSelection"));
        assertEquals(true, report.get("reloadMatched"));
        assertTrue(report.get("orderedByValidationF1").toString().contains("não é promovido")
                || report.get("orderedByValidationF1").toString().contains("empate"));
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnosis = (Map<String, Object>) report.get("diagnosis");
        assertEquals(40, ((Number) diagnosis.get("identicalOperationVectors")).intValue());
        assertTrue(Files.size(dir.resolve("models/logistic-context.ser")) > 100);
        SavedModel loaded = SavedModel.load(dir.resolve("models/random-forest-context.ser"));
        assertEquals(ContextFeatures.NAMES.length, loaded.featureNames().length);
    }
}
