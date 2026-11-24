package com.contextshield.core.api;

import com.contextshield.core.TestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CheckApiTest extends TestSupport {

    @Test
    void contratoPublicoDoVerificador() throws Exception {
        JsonNode delivery = read(call(post("/api/check"), null, Map.of("text",
                "Sua encomenda está retida. Pague a taxa de R$ 12,90 em bit.ly/entrega-retida hoje ou ela volta ao remetente."))
                .andExpect(status().isOk()));
        assertThat(delivery.path("level").asText()).isIn("RED", "YELLOW");
        assertThat(delivery.path("score").isMissingNode()).isFalse();
        assertThat(delivery.path("verdict").asText()).isIn("alto", "atencao");
        assertThat(delivery.path("signals").isArray()).isTrue();

        JsonNode normal = read(call(post("/api/check"), null, Map.of("text",
                "Oi! Seu pedido NEXA saiu para entrega e chega amanhã entre 9h e 18h."))
                .andExpect(status().isOk()));
        assertThat(normal.path("level").asText()).isEqualTo("GRAY");

        JsonNode official = read(call(post("/api/check"), null, Map.of("text", "0800 000 0000"))
                .andExpect(status().isOk()));
        assertThat(official.path("level").asText()).isEqualTo("GREEN");

        call(post("/api/check"), null, Map.of("text", "")).andExpect(status().isBadRequest());
        call(post("/api/check"), null, Map.of("text", "x".repeat(4001))).andExpect(status().isBadRequest());
    }
}
