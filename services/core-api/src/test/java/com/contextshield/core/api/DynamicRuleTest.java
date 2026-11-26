package com.contextshield.core.api;

import com.contextshield.core.TestSupport;
import com.contextshield.core.service.RiskRuleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DynamicRuleTest extends TestSupport {

    @Autowired
    RiskRuleService rules;

    @Test
    void seededRulesArePublished() throws Exception {
        int count = rules.activeRulePayload(1L).size();
        assertEquals(RiskRuleService.builtinRuleCount(), count);
        call(get("/api/ops/rules/catalog"), analyst(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedCount").value(count));
    }

    @Test
    void r13LifecycleWithoutRestart() throws Exception {
        var postRule = call(post("/api/ops/rules"), analyst(), Map.of(
                "ruleKey", "R199",
                "name", "Destino em incidente",
                "summary", "Bloqueia quando o destino aparece em incidente e o score passa do médio",
                "priority", 15,
                "kind", "SCORE_SIGNAL",
                "params", Map.of("weightKey", "THRESHOLD_MEDIUM", "signal", "DESTINATION_IN_INCIDENT", "stage", "any"),
                "outcome", "BLOCK",
                "note", "Teste IT"));
        postRule.andExpect(status().isOk());
        long id = read(postRule).path("id").asLong();
        call(post("/api/ops/rules/" + id + "/submit"), analyst(), Map.of()).andExpect(status().isOk());
        call(post("/api/admin/rules/" + id + "/approve"), admin(), Map.of()).andExpect(status().isOk());
        call(post("/api/admin/rules/" + id + "/publish"), admin(), Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        int withR13 = rules.activeRulePayload(1L).size();
        assertTrue(withR13 > RiskRuleService.builtinRuleCount());
        call(post("/api/ops/rules/" + id + "/disable"), analyst(), Map.of()).andExpect(status().isOk());
        assertEquals(RiskRuleService.builtinRuleCount(), rules.activeRulePayload(1L).size());
        var archived = rules.list(1L).stream().filter(r -> "ARCHIVED".equals(r.get("status")) || "DISABLED".equals(r.get("status"))).findFirst();
        assertTrue(archived.isPresent());
    }
}