package com.contextshield.core.api;

import com.contextshield.core.TestSupport;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** CRUD pela API: relatos (cliente), avisos (cliente) e usuários (admin). */
class IncidentAlertAdminApiTest extends TestSupport {

    private Map<String, Object> incident(String description) {
        return Map.of("type", "message", "channel", "sms", "description", description,
                "contactLink", "golpe-entrega.site", "consent", true);
    }

    @Test
    void crudDeRelatoPeloCliente() throws Exception {
        String rafael = login("rafael.nunes@correio.example");
        var created = read(call(post("/api/incidents"), rafael, incident("SMS dizendo que minha encomenda estava retida.")).andExpect(status().isCreated()));
        String id = created.path("id").asText();

        call(get("/api/incidents/" + id), rafael, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));
        call(put("/api/incidents/" + id), rafael, incident("SMS dizendo que minha encomenda estava retida e pedindo taxa."))
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").value("SMS dizendo que minha encomenda estava retida e pedindo taxa."));
        call(get("/api/incidents/" + id), ana(), null).andExpect(status().isNotFound());
        call(delete("/api/incidents/" + id), rafael, null).andExpect(status().isNoContent());
        call(get("/api/incidents/" + id), rafael, null).andExpect(status().isNotFound());
    }

    @Test
    void relatoEmAnaliseNaoPodeSerAlteradoEAnalistaEncerra() throws Exception {
        String rafael = login("rafael.nunes@correio.example");
        String id = read(call(post("/api/incidents"), rafael, incident("Recebi um link falso pedindo para validar meus dados.")))
                .path("id").asText();
        String analyst = analyst();
        call(post("/api/ops/incidents/" + id + "/assign"), analyst, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_REVIEW"));
        call(put("/api/incidents/" + id), rafael, incident("Tentando mudar depois da triagem.")).andExpect(status().isConflict());
        call(post("/api/ops/incidents/" + id + "/close"), analyst, Map.of("as", "scam", "note", "Golpe confirmado"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.closedAs").value("scam"));
        call(post("/api/ops/incidents/" + id + "/close"), analyst, Map.of("as", "scam", "note", "De novo")).andExpect(status().isConflict());
    }

    @Test
    void respostaFicaNoRelatoComQuemEstaAutenticado() throws Exception {
        String rafael = login("rafael.nunes@correio.example");
        var created = read(call(post("/api/incidents"), rafael, incident("Recebi uma ligação pedindo o código do cartão.")).andExpect(status().isCreated()));
        String id = created.path("id").asText();
        String other = read(call(post("/api/incidents"), rafael, incident("Outro relato que não deve receber esta resposta.")).andExpect(status().isCreated())).path("id").asText();
        String analyst = analyst();
        String text = "Olhei o caso e vou seguir com a análise.";

        call(post("/api/ops/incidents/" + id + "/replies"), null, Map.of("body", text)).andExpect(status().isUnauthorized());
        call(post("/api/ops/incidents/" + id + "/replies"), rafael, Map.of("body", text)).andExpect(status().isForbidden());
        call(post("/api/ops/incidents/" + id + "/replies"), analyst, Map.of("body", "   ")).andExpect(status().isBadRequest());
        call(post("/api/ops/incidents/CS-NAO-EXISTE/replies"), analyst, Map.of("body", text)).andExpect(status().isNotFound());

        var sent = read(call(post("/api/ops/incidents/" + id + "/replies"), analyst,
                Map.of("body", "  " + text + "  ", "authorEmail", "admin@contextshield.example", "authorName", "Outra pessoa"))
                .andExpect(status().isOk()));
        assertThat(sent.path("incident").path("status").asText()).isEqualTo("IN_REVIEW");
        assertThat(sent.path("replies")).hasSize(1);
        var first = sent.path("replies").get(0);
        assertThat(first.path("incidentId").asText()).isEqualTo(id);
        assertThat(first.path("body").asText()).isEqualTo(text);
        assertThat(first.path("authorEmail").asText()).isEqualTo("analista@contextshield.example");
        assertThat(first.path("authorName").asText()).isEqualTo("Lívia Moreira");
        assertThat(first.path("serviceStatus").asText()).isEqualTo("IN_REVIEW");
        assertThat(first.path("createdAt").asText()).isNotBlank();
        assertThat(sent.path("attendance").path("complainantId").asText()).isEqualTo(created.path("customerId").asText());
        assertThat(sent.path("attendance").path("attendant").path("email").asText()).isEqualTo("analista@contextshield.example");
        assertThat(sent.path("attendance").path("attendant").path("name").asText()).isEqualTo("Lívia Moreira");
        assertThat(sent.path("attendance").has("decision")).isFalse();

        var second = read(call(post("/api/ops/incidents/" + id + "/replies"), analyst, Map.of("body", "Segunda resposta, ainda sem encerrar."))
                .andExpect(status().isOk()));
        assertThat(second.path("incident").path("status").asText()).isEqualTo("IN_REVIEW");
        assertThat(second.path("replies")).hasSize(2);
        assertThat(second.path("replies").get(0).path("id").asLong()).isLessThan(second.path("replies").get(1).path("id").asLong());
        assertThat(second.path("replies").get(1).path("body").asText()).isEqualTo("Segunda resposta, ainda sem encerrar.");

        var loaded = read(call(get("/api/ops/incidents/" + id), analyst, null).andExpect(status().isOk()));
        assertThat(loaded.path("replies")).hasSize(2);
        assertThat(loaded.path("replies").get(0).path("body").asText()).isEqualTo(text);
        assertThat(read(call(get("/api/ops/incidents/" + other), analyst, null)).path("replies")).isEmpty();

        var replied = read(call(get("/api/ops/audit?action=INCIDENT_REPLIED&q=" + id), analyst, null).andExpect(status().isOk()));
        assertThat(replied.path("total").asLong()).isEqualTo(2);
        assertThat(replied.path("items").get(0).path("actor").asText()).isEqualTo("analista@contextshield.example");
        assertThat(replied.path("items").get(0).path("target").asText()).isEqualTo(id);
        assertThat(replied.path("items").get(0).path("action").asText()).isEqualTo("INCIDENT_REPLIED");
        var assigned = read(call(get("/api/ops/audit?action=INCIDENT_ASSIGNED&q=" + id), analyst, null).andExpect(status().isOk()));
        assertThat(assigned.path("total").asLong()).isEqualTo(1);
        assertThat(assigned.path("items").get(0).path("action").asText()).isEqualTo("INCIDENT_ASSIGNED");

        call(post("/api/ops/incidents/" + id + "/close"), analyst, Map.of("as", "scam", "note", "Golpe confirmado na ligação"))
                .andExpect(status().isOk());
        var closed = read(call(get("/api/ops/incidents/" + id), analyst, null).andExpect(status().isOk()));
        assertThat(closed.path("replies")).hasSize(2);
        closed.path("replies").forEach(reply -> assertThat(reply.path("body").asText()).doesNotContain("Golpe confirmado"));
        assertThat(closed.path("attendance").path("decision").path("email").asText()).isEqualTo("analista@contextshield.example");
        call(post("/api/ops/incidents/" + id + "/replies"), analyst, Map.of("body", "Tentativa depois do encerramento."))
                .andExpect(status().isConflict());
        assertThat(read(call(get("/api/ops/incidents/" + id), analyst, null)).path("replies")).hasSize(2);
        var trail = read(call(get("/api/ops/audit?size=5"), analyst, null).andExpect(status().isOk()));
        assertThat(trail.path("items").size()).isGreaterThan(0);
        assertThat(trail.path("total").asLong()).isGreaterThanOrEqualTo(trail.path("items").size());
    }

    @Test
    void relatoSemConsentimentoOuCurtoEhRecusado() throws Exception {
        String rafael = login("rafael.nunes@correio.example");
        call(post("/api/incidents"), rafael, Map.of("type", "message", "channel", "sms", "description", "curto", "consent", false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.consent").exists())
                .andExpect(jsonPath("$.fields.description").exists());
        call(post("/api/incidents"), rafael, Map.of("type", "inventado", "channel", "sms", "description", "Descrição longa o bastante.", "consent", true))
                .andExpect(status().isBadRequest());
    }

    @Test
    void avisosDoClienteListarLerApagar() throws Exception {
        String analyst = analyst();
        var sent = read(call(post("/api/ops/alerts"), analyst, Map.of("customerId", "CUST-006", "title", "Teste de aviso", "body", "Mensagem do analista"))
                .andExpect(status().isCreated()));
        long id = sent.path("id").asLong();
        String diego = login("diego.faria@correio.example");
        var list = read(call(get("/api/alerts"), diego, null).andExpect(status().isOk()));
        assertThat(list.findValuesAsText("title")).contains("Teste de aviso");
        call(patch("/api/alerts/" + id + "/read"), diego, null).andExpect(status().isOk()).andExpect(jsonPath("$.readAt").exists());
        call(patch("/api/alerts/" + id + "/read"), ana(), null).andExpect(status().isNotFound());
        call(delete("/api/alerts/" + id), diego, null).andExpect(status().isNoContent());
    }

    @Test
    void crudDeUsuariosPeloAdmin() throws Exception {
        String admin = admin();
        var created = read(call(post("/api/admin/users"), admin,
                Map.of("email", "thiago.analista@contextshield.example", "name", "Thiago", "password", "senha-forte-1", "role", "ANALYST"))
                .andExpect(status().isCreated()));
        long id = created.path("id").asLong();
        call(get("/api/admin/users/" + id), admin, null).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ANALYST"));
        call(put("/api/admin/users/" + id), admin, Map.of("name", "Thiago B.", "active", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Thiago B."));
        // usuário desativado não entra
        call(post("/api/auth/login"), null, Map.of("email", "thiago.analista@contextshield.example", "password", "senha-forte-1"))
                .andExpect(status().isUnauthorized());
        call(delete("/api/admin/users/" + id), admin, null).andExpect(status().isNoContent());
        call(get("/api/admin/users/" + id), admin, null).andExpect(status().isNotFound());
        call(post("/api/admin/users"), admin, Map.of("email", "ana.martins@correio.example", "name", "Dup", "password", "senha-forte-1", "role", "ANALYST"))
                .andExpect(status().isConflict());
    }
}
