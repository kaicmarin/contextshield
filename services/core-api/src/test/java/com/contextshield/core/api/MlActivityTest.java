package com.contextshield.core.api;

import com.contextshield.core.TestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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