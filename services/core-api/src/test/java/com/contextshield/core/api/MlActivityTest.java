package com.contextshield.core.api;

import com.contextshield.core.TestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import org.springframework.web.util.UriComponentsBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


class MlActivityTest extends TestSupport {

    @Autowired JdbcTemplate jdbc;

    @Test
    void emptyActivityReturnsStructure() throws Exception {
        call(get("/api/ops/ml/activity/summary"), analyst(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.predictions").isNumber())
                .andExpect(jsonPath("$.shadowMode").value(true))
                .andExpect(jsonPath("$.charts.predictionsByDay").isArray());
        call(get("/api/ops/ml/activity"), analyst(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.shadowMode").value(true));
    }

    @Test
    void shadowRowAppearsInFeed() throws Exception {
        jdbc.update("insert into ml_shadow (tx_id, model_id, algorithm, prediction, probability, engine_decision, latency_ms, at, stage, organization_id, error) values (?, null, ?, ?, ?, ?, ?, ?, ?, ?, null)",
                "TX-ACT-1", "logistic-regression", "FRAUD", 0.72, "APPROVE", 4.2,
                Timestamp.valueOf(LocalDateTime.now().withNano(0)), "initial", 1L);
        try {
            call(get("/api/ops/ml/activity?prediction=FRAUD&q=TX-ACT"), analyst(), null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[?(@.txId == 'TX-ACT-1')]").exists());
        } finally {
            jdbc.update("delete from ml_shadow where tx_id = ?", "TX-ACT-1");
        }
    }

    @Test
    void modelActivityWithNullExperimentFields() throws Exception {
        jdbc.update("""
                insert into ml_experiment (organization_id, status, kind, seed, example_count, requested_by, created_at, finished_at, error)
                values (1, 'RUNNING', 'synthetic', 1, 100, 'test', ?, null, null)
                """, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
        long expId = jdbc.queryForObject("select max(id) from ml_experiment", Long.class);
        jdbc.update("""
                insert into ml_model (algorithm, feature_set, status, organization_id, experiment_id, example_count, created_at)
                values ('x', 'operation', 'TRAINED', 1, ?, 100, ?)
                """, expId, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
        long modelId = jdbc.queryForObject("select max(id) from ml_model", Long.class);
        call(get("/api/ops/ml/models/" + modelId + "/activity"), analyst(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.experiments[0].finishedAt").doesNotExist())
                .andExpect(jsonPath("$.experiments[0].error").doesNotExist());
    }

    @Test
    void paginationDoesNotDuplicateAcrossPages() throws Exception {
        LocalDateTime base = LocalDateTime.now().withNano(0);
        for (int i = 0; i < 30; i++) {
            jdbc.update("insert into ml_shadow (tx_id, algorithm, prediction, engine_decision, at, stage, organization_id, error) values (?, 'a', 'LEGIT', 'APPROVE', ?, 'initial', 1, null)",
                    "TX-PG-" + i, Timestamp.valueOf(base.minusMinutes(i)));
        }
        try {
            var p1 = call(get("/api/ops/ml/activity?size=10&q=TX-PG&eventType=PREDICTION"), analyst(), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var node1 = json.readTree(p1);
            assertEquals(10, node1.path("items").size());
            String c1 = node1.path("nextCursor").asText();
            assertTrue(node1.path("hasMore").asBoolean());
            String page2 = UriComponentsBuilder.fromPath("/api/ops/ml/activity")
                    .queryParam("size", 10).queryParam("q", "TX-PG").queryParam("eventType", "PREDICTION")
                    .queryParam("cursor", c1).build().toUriString();
            var p2 = call(get(page2), analyst(), null)
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var node2 = json.readTree(p2);
            assertFalse(node2.path("items").isEmpty());
            var keys1 = new java.util.HashSet<String>();
            node1.path("items").forEach(n -> keys1.add(n.path("key").asText()));
            node2.path("items").forEach(n -> assertFalse(keys1.contains(n.path("key").asText()), "chave duplicada entre páginas"));
        } finally {
            jdbc.update("delete from ml_shadow where tx_id like 'TX-PG-%'");
        }
    }

    @Test
    void modelActivityNotFoundInOrganization() throws Exception {
        call(get("/api/ops/ml/models/999999/activity"), analyst(), null).andExpect(status().isNotFound());
    }

    @Test
    void otherOrganizationDoesNotSeeActivity() throws Exception {
        jdbc.update("insert into ml_shadow (tx_id, algorithm, prediction, engine_decision, at, stage, organization_id) values (?, ?, 'LEGIT', 'APPROVE', ?, 'initial', 2)",
                "TX-ORG2", "x", Timestamp.valueOf(LocalDateTime.now().withNano(0)));
        try {
            call(get("/api/ops/ml/activity?q=TX-ORG2"), analyst(), null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[?(@.txId == 'TX-ORG2')]").doesNotExist());
        } finally {
            jdbc.update("delete from ml_shadow where tx_id = ?", "TX-ORG2");
        }
    }
}