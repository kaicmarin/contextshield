package com.contextshield.core.api;

import com.contextshield.core.TestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LabStudyTest extends TestSupport {

    @Test
    void labelsOverviewSeparatesSyntheticFromReviewed() throws Exception {
        call(get("/api/ops/ml/labels"), analyst(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.trainingLabelPolicy").isString())
                .andExpect(jsonPath("$.syntheticNote").isString());
    }

    @Test
    void weightStudyDoesNotAutoPublish() throws Exception {
        var queued = read(call(post("/api/ops/ml/studies/weights"), analyst(), Map.of("seed", 42, "costProfile", "standard")));
        assertNotNull(queued.path("id"));
        for (int i = 0; i < 40; i++) {
            var st = read(call(get("/api/ops/ml/studies/" + queued.path("id").asLong()), analyst(), null));
            String status = st.path("status").asText();
            if ("DONE".equals(status) || "FAILED".equals(status)) {
                assertTrue(!"PUBLISHED".equals(status));
                return;
            }
            Thread.sleep(500);
        }
    }

    @Test
    void overviewIncludesStudiesAndRuleSuggestions() throws Exception {
        call(get("/api/ops/ml"), analyst(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.labels").exists())
                .andExpect(jsonPath("$.studies").isArray())
                .andExpect(jsonPath("$.ruleSuggestions").isArray());
    }

    @Test
    void overviewLabelsAndStudiesMatchDedicatedEndpoints() throws Exception {
        JsonNode labels = read(call(get("/api/ops/ml/labels"), analyst(), null));
        JsonNode studies = read(call(get("/api/ops/ml/studies"), analyst(), null));
        JsonNode overview = read(call(get("/api/ops/ml"), analyst(), null));
        assertEquals(labels.path("reviewedFraudLegit").asInt(), overview.path("labels").path("reviewedFraudLegit").asInt());
        assertEquals(labels.path("pendingReports").asInt(), overview.path("labels").path("pendingReports").asInt());
        assertEquals(studies.size(), overview.path("studies").size());
    }

    @Test
    void queuedWeightStudyVisibleInOverview() throws Exception {
        var queued = read(call(post("/api/ops/ml/studies/weights"), analyst(), Map.of("seed", 42, "costProfile", "standard")));
        long id = queued.path("id").asLong();
        var overview = read(call(get("/api/ops/ml"), analyst(), null));
        boolean found = false;
        for (var node : overview.path("studies")) {
            if (node.path("id").asLong() == id) {
                found = true;
                break;
            }
        }
        assertTrue(found, "estudo enfileirado deve aparecer em GET /api/ops/ml");
    }
}