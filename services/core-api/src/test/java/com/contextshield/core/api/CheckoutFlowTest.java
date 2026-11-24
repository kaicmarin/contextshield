package com.contextshield.core.api;

import com.contextshield.core.TestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Testes funcionais do fluxo principal: checkout → motor → verificação → intervenção → relato → bloqueio. */
class CheckoutFlowTest extends TestSupport {

    private static final String INDUCTION_SCRIPT = String.join("\n",
            "Olá, Ana. Identificamos uma operação suspeita no seu cartão final 4417.",
            "Para cancelar, faça o procedimento de segurança em aureon-protecao.example",
            "Abra a NEXA e compre o notebook indicado. O valor é estornado em seguida.",
            "Use o endereço Rua Pedra Lisa, 880 — loja 3. É o nosso ponto de retirada.",
            "Se aparecer alguma pergunta de segurança, responda que ninguém está ajudando você.");

    private JsonNode checkout(String token, Map<String, Object> body, int expected) throws Exception {
        return read(call(post("/api/transactions"), token, body).andExpect(status().is(expected)));
    }

    @Test
    void compraHabitualEAprovada() throws Exception {
        var tx = checkout(ana(), Map.of("items", List.of(Map.of("productId", "prod-010", "quantity", 1)),
                "addressId", "ADDR-ANA-HOME", "deviceId", "DEV-ANA-MBA", "declaredOrigin", "self"), 201);
        assertThat(tx.path("status").asText()).isEqualTo("APPROVED");
        assertThat(tx.path("band").asText()).isEqualTo("LOW");
    }

    @Test
    void inducaoPedeContextoPausaEClienteCancela() throws Exception {
        String ana = ana();
        var tx = checkout(ana, Map.of("items", List.of(Map.of("productId", "prod-001", "quantity", 1)),
                "addressId", "ADDR-PICKUP-880", "deviceId", "DEV-ANA-W11", "declaredOrigin", "link",
                "coachScript", INDUCTION_SCRIPT), 201);
        assertThat(tx.path("status").asText()).isEqualTo("CONTEXT_REQUIRED");
        List<String> keys = tx.path("verification").path("questions").findValuesAsText("key");
        assertThat(keys).contains("origin", "contact", "coached");

        // Respostas que repetem o roteiro ("ninguém está me ajudando") → R10, espelho do roteiro
        var after = read(call(post("/api/transactions/" + tx.path("id").asText() + "/verification"), ana,
                Map.of("answers", Map.of("origin", "self", "contact", "no", "coached", "no", "anchor", "home")))
                .andExpect(status().isOk()));
        assertThat(after.path("status").asText()).isEqualTo("INTERVENTION");

        var cancelled = read(call(post("/api/transactions/" + tx.path("id").asText() + "/resolution"), ana,
                Map.of("choice", "cancel")).andExpect(status().isOk()));
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");

        // Estado final não reabre, nem pelo analista
        call(post("/api/ops/transactions/" + tx.path("id").asText() + "/transition"), analyst(),
                Map.of("to", "APPROVED", "reason", "teste")).andExpect(status().isConflict());
    }

    @Test
    void relatoDeUmClienteBloqueiaCompraDeOutroNoMesmoDestino() throws Exception {
        String ana = ana();
        var tx = checkout(ana, Map.of("items", List.of(Map.of("productId", "prod-001", "quantity", 1)),
                "addressId", "ADDR-PICKUP-880", "deviceId", "DEV-ANA-W11", "declaredOrigin", "link"), 201);
        call(post("/api/incidents"), ana, Map.of("txId", tx.path("id").asText(), "type", "impersonation", "channel", "whatsapp",
                "contactHandle", "(11) 90000-2184", "destinationText", "Rua Pedra Lisa, 880 — loja 3",
                "description", "Uma falsa central pediu para comprar um notebook e entregar na Rua Pedra Lisa, 880.", "consent", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("CS-2026-")));

        String mariana = login("mariana.rocha@correio.example");
        var related = checkout(mariana, Map.of("items", List.of(Map.of("productId", "prod-024", "quantity", 1)),
                "addressId", "ADDR-PICKUP-880", "deviceId", "DEV-MAR-IPH", "declaredOrigin", "guided"), 201);
        assertThat(related.path("status").asText()).isEqualTo("BLOCKED");
        assertThat(related.path("reasons").findValuesAsText("label"))
                .contains("Este endereço de entrega aparece em relatos recentes de outras pessoas");

        // O analista vê a relação na inteligência
        var entities = read(call(get("/api/ops/intelligence"), analyst(), null).andExpect(status().isOk()));
        assertThat(entities.findValuesAsText("id")).contains("ENT-DEST-PICKUP-880");
    }

    @Test
    void clienteNaoVeCompraDeOutroCliente() throws Exception {
        var tx = checkout(ana(), Map.of("items", List.of(Map.of("productId", "prod-010", "quantity", 1)),
                "addressId", "ADDR-ANA-HOME", "declaredOrigin", "self"), 201);
        call(get("/api/transactions/" + tx.path("id").asText()), login("carlos.menezes@correio.example"), null)
                .andExpect(status().isNotFound());
    }

    @Test
    void validaEntradaDoCheckout() throws Exception {
        String ana = ana();
        call(post("/api/transactions"), ana, Map.of("items", List.of(), "declaredOrigin", "self"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.items").exists());
        call(post("/api/transactions"), ana, Map.of("items", List.of(Map.of("productId", "nao-existe", "quantity", 1)),
                "addressId", "ADDR-ANA-HOME", "declaredOrigin", "self")).andExpect(status().isBadRequest());
        call(post("/api/transactions"), ana, Map.of("items", List.of(Map.of("productId", "prod-010", "quantity", 1)),
                "addressId", "ADDR-CAR-HOME", "declaredOrigin", "self")).andExpect(status().isBadRequest());
        call(post("/api/transactions"), ana, Map.of("items", List.of(Map.of("productId", "prod-010", "quantity", 1)),
                "addressId", "ADDR-ANA-HOME", "deviceId", "DEV-CAR-PIX", "declaredOrigin", "self")).andExpect(status().isBadRequest());
        call(post("/api/transactions"), ana, "{ isto não é json").andExpect(status().isBadRequest());
    }

    @Test
    void respostaDeVerificacaoInvalidaOuForaDeHoraEhRecusada() throws Exception {
        String ana = ana();
        var tx = checkout(ana, Map.of("items", List.of(Map.of("productId", "prod-001", "quantity", 1)),
                "addressId", "ADDR-PICKUP-880", "deviceId", "DEV-ANA-W11", "declaredOrigin", "link"), 201);
        String id = tx.path("id").asText();
        call(post("/api/transactions/" + id + "/verification"), ana, Map.of("answers", Map.of("origin", "talvez")))
                .andExpect(status().isBadRequest());
        call(post("/api/transactions/" + id + "/resolution"), ana, Map.of("choice", "proceed"))
                .andExpect(status().isConflict());
    }

    @Test
    void motorForaDoArNaoAprovaACompra() throws Exception {
        engine.down = true;
        var tx = checkout(ana(), Map.of("items", List.of(Map.of("productId", "prod-010", "quantity", 1)),
                "addressId", "ADDR-ANA-HOME", "declaredOrigin", "self"), 202);
        assertThat(tx.path("status").asText()).isEqualTo("ANALYZING");
        engine.down = false;
        var retried = read(call(post("/api/transactions/" + tx.path("id").asText() + "/retry"), ana(), null).andExpect(status().isOk()));
        assertThat(retried.path("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void cenariosDaDemonstracaoBatemComOPrototipo() throws Exception {
        String analyst = analyst();
        Map<String, String> expected = Map.of("normal", "APPROVED", "attention", "APPROVED_WITH_ALERT",
                "hard", "INTERVENTION", "induction", "INTERVENTION", "fragile", "APPROVED_WITH_ALERT");
        for (var e : expected.entrySet()) {
            // cada cenário parte da história limpa, como na demonstração; a ordem do Map.of é aleatória
            seed.resetDynamicData();
            var r = read(call(post("/api/ops/demo/scenarios/" + e.getKey() + "/run"), analyst, "{}").andExpect(status().isOk()));
            assertThat(r.path("summary").path("status").asText()).as(e.getKey()).isEqualTo(e.getValue());
        }
    }

    @Test
    void verificadorReconheceMensagemDeGolpe() throws Exception {
        var r = read(call(post("/api/check"), null, Map.of("text", INDUCTION_SCRIPT)).andExpect(status().isOk()));
        assertThat(r.path("level").asText()).isEqualTo("RED");
        var ok = read(call(post("/api/check"), null, Map.of("text", "0800 000 0000")).andExpect(status().isOk()));
        assertThat(ok.path("level").asText()).isEqualTo("GREEN");
    }
}
