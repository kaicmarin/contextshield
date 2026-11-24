package com.contextshield.ml;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingTest {

    @Test
    void trainsSavesReloadsAndSearchesWeights() throws Exception {
        Path dir = Files.createTempDirectory("cs-ml");
        Map<String, Object> report = Experiment.run(400, 7L, dir);
        assertEquals(400, ((Number) report.get("generatedExamples")).intValue());
        assertTrue((Boolean) report.get("reloadMatchedTraining"));
        assertTrue(Files.size(dir.resolve("models/logistic-regression.ser")) > 100);
        assertTrue(Files.size(dir.resolve("models/random-forest.ser")) > 100);
        @SuppressWarnings("unchecked")
        Map<String, Object> optimization = (Map<String, Object>) report.get("optimization");
        assertEquals(false, optimization.get("published"));
        assertTrue(((Number) ((Map<?, ?>) optimization.get("baselineSearch")).get("cost")).intValue() >= 0);
        assertFalse(optimization.get("note").toString().isBlank());
        SavedModel loaded = SavedModel.load(dir.resolve("models/logistic-regression.ser"));
        double[] row = Features.vector(SyntheticCases.generate(60, 7L).get(0).checkout());
        assertTrue(loaded.predict(row).fraudProbability() >= 0);
        assertTrue(loaded.predict(row).fraudProbability() <= 1);
    }

    @Test
    void defaultWeightsMatchTheCatalog() {
        assertEquals(8, com.contextshield.risk.engine.Weights.value(Map.of(), "NEW_DEVICE"));
        assertEquals(16, com.contextshield.risk.engine.Weights.value(Map.of("NEW_DEVICE", 99), "NEW_DEVICE"));
        assertEquals(20, com.contextshield.risk.engine.Weights.value(Map.of("DESTINATION_IN_INCIDENT", 1), "DESTINATION_IN_INCIDENT"));
    }
}
