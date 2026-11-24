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

    @Test
    void anotherOrganizationSeesAnEmptyQueue() throws Exception {
        jdbc.update("update app_user set organization_id = 2 where email = 'analista@contextshield.example'");
        try {
            call(get("/api/ops/transactions"), analyst(), null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            call(get("/api/ops/incidents"), analyst(), null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
            call(get("/api/ops/intelligence"), analyst(), null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
            call(get("/api/ops/overview"), analyst(), null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions").value(0))
                    .andExpect(jsonPath("$.window.scope").value("organization"));
            call(get("/api/ops/audit"), analyst(), null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[*].actor").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo("analista@contextshield.example"))));
            call(get("/api/ops/policies"), analyst(), null).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.profile == 'fintech')]").isEmpty())
                    .andExpect(jsonPath("$[?(@.profile == 'marketplace')].profile").value(org.hamcrest.Matchers.hasItem("marketplace")));
            call(get("/api/ops/policies/1"), analyst(), null).andExpect(status().isNotFound());
            call(get("/api/ops/ml"), analyst(), null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.models.length()").value(0));
            call(get("/api/ops/ml/experiments/1"), analyst(), null).andExpect(status().isNotFound());
        } finally {
            jdbc.update("update app_user set organization_id = 1 where email = 'analista@contextshield.example'");
        }
    }

    @Test
    void contextualVerificationExpiresWithoutApproving() throws Exception {
        jdbc.update("delete from engine_setting where setting_key = 'flag.context-when-unknown'");
        jdbc.update("insert into engine_setting (setting_key, setting_value) values ('flag.context-when-unknown', 'true')");
        try {
            var tx = read(call(post("/api/transactions"), ana(), Map.of(
                    "items", java.util.List.of(Map.of("productId", "prod-010", "quantity", 1)),
                    "addressId", "ADDR-ANA-HOME", "deviceId", "DEV-ANA-MBA", "declaredOrigin", "link"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("CONTEXT_REQUIRED")));
            String id = tx.path("id").asText();
            call(get("/api/ops/transactions/" + id), analyst(), null)
                    .andExpect(jsonPath("$.verificationState").value("REQUESTED"))
                    .andExpect(jsonPath("$.summary.ruleId").value(org.hamcrest.Matchers.anyOf(
                            org.hamcrest.Matchers.equalTo("R6"), org.hamcrest.Matchers.equalTo("R5"))));
            jdbc.update("update verification set expires_at = timestamp '2020-01-01 00:00:00' where tx_id = ?", id);
            call(post("/api/transactions/" + id + "/verification"), ana(), Map.of("answers", Map.of("origin", "self", "contact", "no", "address", "mine", "coached", "no")))
                    .andExpect(status().isConflict());
            call(get("/api/transactions/" + id), ana(), null).andExpect(jsonPath("$.status").value("CONTEXT_REQUIRED"));
        } finally {
            jdbc.update("delete from engine_setting where setting_key = 'flag.context-when-unknown'");
        }
    }

    @Test
    void unreviewedReportDoesNotBecomeATrainingLabel() throws Exception {
        var tx = read(call(post("/api/transactions"), ana(), Map.of(
                "items", java.util.List.of(Map.of("productId", "prod-010", "quantity", 1)),
                "addressId", "ADDR-ANA-HOME", "deviceId", "DEV-ANA-MBA", "declaredOrigin", "self")).andExpect(status().isCreated()));
        call(post("/api/admin/outcomes/reports"), admin(), Map.of("txId", tx.path("id").asText(), "label", "FRAUD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedAsTrainingLabel").value(false))
                .andExpect(jsonPath("$.reviewState").value("PENDING"));
        call(get("/api/ops/ml/monitor"), analyst(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qualityAvailable").value(false));
    }

    @Test
    void frequencyCapKeepsASelectiveProbeFromAskingAgain() throws Exception {
        jdbc.update("delete from engine_setting where setting_key in ('flag.context-when-unknown', 'context-probe-max')");
        jdbc.update("insert into engine_setting (setting_key, setting_value) values ('flag.context-when-unknown', 'true')");
        jdbc.update("insert into engine_setting (setting_key, setting_value) values ('context-probe-max', '0')");
        try {
            var tx = read(call(post("/api/transactions"), ana(), Map.of(
                    "items", java.util.List.of(Map.of("productId", "prod-010", "quantity", 1)),
                    "addressId", "ADDR-ANA-HOME", "deviceId", "DEV-ANA-MBA", "declaredOrigin", "ad"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.not("CONTEXT_REQUIRED"))));
            call(get("/api/ops/transactions/" + tx.path("id").asText()), analyst(), null)
                    .andExpect(jsonPath("$.summary.ruleId").value(org.hamcrest.Matchers.not("R12")));
        } finally {
            jdbc.update("delete from engine_setting where setting_key in ('flag.context-when-unknown', 'context-probe-max')");
        }
    }

    @Test
    void declaredDenialStaysInconclusiveWithoutIndependentEvidence() throws Exception {
        var tx = read(call(post("/api/transactions"), ana(), Map.of(
                "items", java.util.List.of(Map.of("productId", "prod-010", "quantity", 1)),
                "addressId", "ADDR-ANA-HOME", "deviceId", "DEV-ANA-MBA", "declaredOrigin", "link"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONTEXT_REQUIRED")));
        String id = tx.path("id").asText();
        call(post("/api/transactions/" + id + "/verification"), ana(), Map.of(
                "answers", Map.of("origin", "self", "contact", "no", "address", "mine", "coached", "no")))
                .andExpect(status().isOk());
        call(get("/api/ops/transactions/" + id), analyst(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationState").value("INCONCLUSIVE"))
                .andExpect(jsonPath("$.verificationReason").value(org.hamcrest.Matchers.containsString("declarada")))
                .andExpect(jsonPath("$.summary.decision").exists());
    }

    @Test
    void contextualShadowUsesCollectedAnswersAndDoesNotDecide() throws Exception {
        java.nio.file.Path model = java.nio.file.Path.of("..", "..", "machine-learning", "models", "logistic-context.ser").toAbsolutePath();
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(model), "modelo contextual ausente");
        jdbc.update("""
                insert into ml_model (algorithm, status, path, validation, decision_authorized, feature_set, created_at)
                values ('logistic-regression-context', 'APPROVED', ?, 'RECORDED', false, 'context', current_timestamp)
                """, model.toString());
        try {
            var tx = read(call(post("/api/transactions"), ana(), Map.of(
                    "items", java.util.List.of(Map.of("productId", "prod-010", "quantity", 1)),
                    "addressId", "ADDR-ANA-HOME", "deviceId", "DEV-ANA-MBA", "declaredOrigin", "link"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("CONTEXT_REQUIRED")));
            String id = tx.path("id").asText();
            call(post("/api/transactions/" + id + "/verification"), ana(), Map.of(
                    "answers", Map.of("origin", "asked", "contact", "phone", "address", "given", "coached", "yes")))
                    .andExpect(status().isOk());
            call(get("/api/ops/transactions/" + id), analyst(), null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.shadow[?(@.algorithm == 'logistic-regression-context')].stage").value(org.hamcrest.Matchers.hasItem("recalculated")))
                    .andExpect(jsonPath("$.shadow[?(@.algorithm == 'logistic-regression-context')].affectsDecision").value(org.hamcrest.Matchers.hasItem(false)))
                    .andExpect(jsonPath("$.shadow[?(@.algorithm == 'logistic-regression-context')].explanation").exists())
                    .andExpect(jsonPath("$.summary.decision").exists());
        } finally {
            jdbc.update("delete from ml_shadow where model_id in (select id from ml_model where path = ?)", model.toString());
            jdbc.update("delete from ml_model where path = ?", model.toString());
        }
    }

    @Test
    void contextModelCanBeApprovedForShadowWithoutPassingTheGateOrReplacingTheOperationModel() throws Exception {
        java.nio.file.Path operation = java.nio.file.Path.of("..", "..", "machine-learning", "models", "logistic-regression.ser").toAbsolutePath();
        java.nio.file.Path context = java.nio.file.Path.of("..", "..", "machine-learning", "models", "logistic-context.ser").toAbsolutePath();
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(operation) && java.nio.file.Files.isRegularFile(context));
        jdbc.update("insert into ml_model (algorithm, status, path, validation, decision_authorized, feature_set, created_at) values ('logistic-regression', 'APPROVED', ?, 'PASS', false, 'operation', current_timestamp)", operation.toString());
        jdbc.update("insert into ml_model (algorithm, status, path, validation, decision_authorized, feature_set, created_at) values ('logistic-regression-context', 'TRAINED', ?, 'RECORDED', false, 'context', current_timestamp)", context.toString());
        long contextId = jdbc.queryForObject("select id from ml_model where path = ?", Long.class, context.toString());
        try {
            call(post("/api/admin/ml/models/" + contextId + "/approve"), admin(), Map.of())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.decisionAuthorized").value(false))
                    .andExpect(jsonPath("$.acceptanceGatePassed").value(false))
                    .andExpect(jsonPath("$.usage").value("shadow"));
            Integer operationApproved = jdbc.queryForObject("select count(*) from ml_model where path = ? and status = 'APPROVED'", Integer.class, operation.toString());
            org.junit.jupiter.api.Assertions.assertEquals(1, operationApproved);
            jdbc.update("update app_user set organization_id = 2 where email = 'admin@contextshield.example'");
            call(post("/api/admin/ml/models/" + contextId + "/approve"), admin(), Map.of()).andExpect(status().isNotFound());
        } finally {
            jdbc.update("update app_user set organization_id = 1 where email = 'admin@contextshield.example'");
            jdbc.update("delete from ml_model where path in (?, ?)", operation.toString(), context.toString());
        }
    }

    @Test
    void anotherOrganizationCannotPublishTheDemoPolicy() throws Exception {
        jdbc.update("update app_user set organization_id = 2 where email = 'admin@contextshield.example'");
        try {
            call(post("/api/admin/policies/1/publish"), admin(), Map.of()).andExpect(status().isNotFound());
            org.junit.jupiter.api.Assertions.assertEquals("PUBLISHED",
                    jdbc.queryForObject("select status from risk_policy where id = 1", String.class));
        } finally {
            jdbc.update("update app_user set organization_id = 1 where email = 'admin@contextshield.example'");
        }
    }

    @Test
    void contextProbeSimulationDoesNotPersist() throws Exception {
        call(get("/api/ops/engine/context-probe"), analyst(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persisted").value(false))
                .andExpect(jsonPath("$.examined").exists());
        call(get("/api/ops/verifications"), analyst(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").exists());
    }
}
