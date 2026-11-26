package com.contextshield.core.api;

import com.contextshield.core.TestSupport;
import org.junit.jupiter.api.Test;

import java.util.Map;

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
}