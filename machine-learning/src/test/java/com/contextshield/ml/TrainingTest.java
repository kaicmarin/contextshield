package com.contextshield.ml;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    void readsTheTimestampShapeSentBetweenServices() {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("id", "C1");
        customer.put("avgTicket90d", 100);
        customer.put("purchases90d", 4);
        customer.put("since", LocalDate.of(2020, 1, 15));
        customer.put("city", "Sao Paulo");
        Map<String, Object> checkout = new LinkedHashMap<>();
        checkout.put("amount", 80);
        checkout.put("customer", customer);
        checkout.put("at", LocalDateTime.of(2026, 11, 24, 10, 0));
        checkout.put("declaredOrigin", "self");
        Map<String, Object> sentCustomer = new LinkedHashMap<>(customer);
        sentCustomer.put("since", List.of(2020, 1, 15));
        Map<String, Object> sent = new LinkedHashMap<>(checkout);
        sent.put("customer", sentCustomer);
        sent.put("at", List.of(2026, 11, 24, 10, 0, 0));
        assertArrayEquals(Features.vector(checkout), Features.vector(sent));
    }

    @Test
    void defaultWeightsMatchTheCatalog() {
        assertEquals(8, com.contextshield.risk.engine.Weights.value(Map.of(), "NEW_DEVICE"));
        assertEquals(16, com.contextshield.risk.engine.Weights.value(Map.of("NEW_DEVICE", 99), "NEW_DEVICE"));
        assertEquals(20, com.contextshield.risk.engine.Weights.value(Map.of("DESTINATION_IN_INCIDENT", 1), "DESTINATION_IN_INCIDENT"));
    }
}
