package com.contextshield.core.api;

import com.contextshield.core.TestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PolicyAndShadowTest extends TestSupport {

    @Autowired JdbcTemplate jdbc;

    @Test
    void publishedPolicyKeepsTheOriginalDeviceWeight() throws Exception {
        call(get("/api/ops/policies/catalog"), analyst(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.signals[?(@.key == 'NEW_DEVICE')].value").value(8))
                .andExpect(jsonPath("$.signals[?(@.key == 'DESTINATION_IN_INCIDENT')].safetyFloor").value(true));
    }

    @Test
    void analystCannotPublishAndADraftCanBeSimulated() throws Exception {
        String analyst = analyst();
        var draft = read(call(post("/api/ops/policies"), analyst, Map.of(
                "name", "Teste de peso", "profile", "fintech", "weights", Map.of("NEW_DEVICE", 16))).andExpect(status().isOk()));
        long id = draft.path("id").asLong();
        call(post("/api/admin/policies/" + id + "/publish"), analyst, Map.of()).andExpect(status().isForbidden());

        var tx = read(call(post("/api/transactions"), ana(), Map.of(
                "items", java.util.List.of(Map.of("productId", "prod-010", "quantity", 1)),
                "addressId", "ADDR-ANA-HOME", "deviceId", "DEV-ANA-MBA", "declaredOrigin", "self")).andExpect(status().isCreated()));
        call(post("/api/ops/policies/" + id + "/simulate"), analyst, Map.of("txId", tx.path("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.originalDecision").exists());
    }

    @Test
    void approvedModelPredictsWithoutChangingTheDecision() throws Exception {
        java.nio.file.Path model = java.nio.file.Path.of("..", "..", "machine-learning", "models", "logistic-regression.ser").toAbsolutePath();
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(model), "modelo treinado ausente");
        jdbc.update("insert into ml_model (algorithm, status, path, validation, decision_authorized, created_at) values ('logistic-regression', 'APPROVED', ?, 'PASS', false, current_timestamp)", model.toString());
        try {
            var tx = read(call(post("/api/transactions"), ana(), Map.of(
                    "items", java.util.List.of(Map.of("productId", "prod-010", "quantity", 1)),
                    "addressId", "ADDR-ANA-HOME", "deviceId", "DEV-ANA-MBA", "declaredOrigin", "self"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("APPROVED")));
            call(get("/api/ops/transactions/" + tx.path("id").asText()), analyst(), null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.shadow[0].affectsDecision").value(false))
                    .andExpect(jsonPath("$.shadow[0].prediction").exists())
                    .andExpect(jsonPath("$.summary.decision").value("APPROVE"));
            long modelId = jdbc.queryForObject("select max(id) from ml_model", Long.class);
            call(post("/api/admin/ml/models/" + modelId + "/decision"), admin(), Map.of())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.decisionAuthorized").value(false));
        } finally {
            jdbc.update("delete from ml_shadow");
            jdbc.update("delete from ml_model where status = 'APPROVED'");
        }
    }

    @Test
    void anotherOrganizationCannotReadTheAssessment() throws Exception {
        jdbc.update("update app_user set organization_id = 2 where email = 'analista@contextshield.example'");
        try {
            var tx = read(call(post("/api/transactions"), ana(), Map.of(
                    "items", java.util.List.of(Map.of("productId", "prod-010", "quantity", 1)),
                    "addressId", "ADDR-ANA-HOME", "deviceId", "DEV-ANA-MBA", "declaredOrigin", "self")).andExpect(status().isCreated()));
            call(get("/api/ops/transactions/" + tx.path("id").asText()), analyst(), null).andExpect(status().isNotFound());
            call(get("/api/v1/partner/assessments/1"), analyst(), null).andExpect(status().isNotFound());
        } finally {
            jdbc.update("update app_user set organization_id = 1 where email = 'analista@contextshield.example'");
        }
    }

    @Test
    void shadowFailureDoesNotChangeTheDecision() throws Exception {
        jdbc.update("insert into ml_model (algorithm, status, path, validation, created_at) values ('logistic-regression', 'APPROVED', 'C:/nao-existe/modelo.ser', 'PASS', current_timestamp)");
        try {
            call(post("/api/transactions"), ana(), Map.of(
                    "items", java.util.List.of(Map.of("productId", "prod-010", "quantity", 1)),
                    "addressId", "ADDR-ANA-HOME", "deviceId", "DEV-ANA-MBA", "declaredOrigin", "self"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("APPROVED"));
        } finally {
            jdbc.update("delete from ml_shadow");
            jdbc.update("delete from ml_model where path = 'C:/nao-existe/modelo.ser'");
        }
    }
}
