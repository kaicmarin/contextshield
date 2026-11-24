package com.contextshield.core.api;

import com.contextshield.core.TestSupport;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalWorkApiTest extends TestSupport {

    @Test
    void customerCannotOpenAnInvestigationAndAnalystSeesTheServiceDown() throws Exception {
        call(post("/api/ops/investigations"), ana(), java.util.Map.of("sourceType", "transaction", "sourceId", "TX-1"))
                .andExpect(status().isForbidden());
        call(get("/api/ops/investigations"), analyst(), null).andExpect(status().isServiceUnavailable());
        call(post("/api/ops/indicators"), analyst(), java.util.Map.of("kind", "url", "value", "http://169.254.169.254/"))
                .andExpect(status().isServiceUnavailable());
    }
}
