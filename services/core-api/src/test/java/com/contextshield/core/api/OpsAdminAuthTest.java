package com.contextshield.core.api;

import com.contextshield.core.TestSupport;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OpsAdminAuthTest extends TestSupport {

    @Test
    void analistaNaoAlteraConfigNemReiniciaDemo() throws Exception {
        var analyst = analyst();
        call(put("/api/ops/engine/config"), analyst, Map.of("key", "script-mirror", "enabled", false))
                .andExpect(status().isForbidden());
        call(post("/api/ops/demo/reset"), analyst, null).andExpect(status().isForbidden());
    }

    @Test
    void adminPodeAlterarConfigDoMotor() throws Exception {
        call(put("/api/ops/engine/config"), admin(), Map.of("key", "script-mirror", "enabled", true))
                .andExpect(status().isOk());
    }
}