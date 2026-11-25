package com.contextshield.core.api;

import com.contextshield.core.TestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthRevocationTest extends TestSupport {

    @Test
    void tokenInvalidoAposDesativarUsuario() throws Exception {
        String token = analyst();
        call(get("/api/ops/overview"), token, null).andExpect(status().isOk());

        String adminToken = admin();
        JsonNode users = read(call(get("/api/admin/users"), adminToken, null).andExpect(status().isOk()));
        long analystId = 0;
        for (JsonNode u : users) {
            if ("analista@contextshield.example".equals(u.path("email").asText())) {
                analystId = u.path("id").asLong();
                break;
            }
        }
        call(put("/api/admin/users/" + analystId), adminToken, Map.of("active", false)).andExpect(status().isOk());
        call(get("/api/ops/overview"), token, null).andExpect(status().isUnauthorized());
        call(put("/api/admin/users/" + analystId), adminToken, Map.of("active", true)).andExpect(status().isOk());
        String fresh = login("analista@contextshield.example");
        call(get("/api/ops/overview"), fresh, null).andExpect(status().isOk());
    }
}