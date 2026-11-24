package com.contextshield.core.api;

import com.contextshield.core.TestSupport;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Período da visão geral e paginação da auditoria. */
class OpsQueryTest extends TestSupport {

    @Test
    void visaoGeralDoPeriodoUsaOMesmoIntervalo() throws Exception {
        var all = read(call(get("/api/ops/overview"), analyst(), null).andExpect(status().isOk()));
        var week = read(call(get("/api/ops/overview?days=7&band=LOW"), analyst(), null).andExpect(status().isOk()));
        assertThat(week.path("window").path("days").asInt()).isEqualTo(7);
        assertThat(week.path("window").path("band").asText()).isEqualTo("LOW");
        assertThat(week.path("window").path("zone").asText()).isEqualTo("America/Sao_Paulo");
        assertThat(week.path("window").path("from").asText()).isNotBlank();
        assertThat(week.path("window").path("bandAppliesToIncidents").asBoolean()).isFalse();
        assertThat(week.path("transactions").asLong()).isLessThanOrEqualTo(all.path("transactions").asLong());
        call(get("/api/ops/overview?days=-1"), analyst(), null).andExpect(status().isBadRequest());
        call(get("/api/ops/overview?band=EXTREMO"), analyst(), null).andExpect(status().isBadRequest());
        call(get("/api/ops/transactions?size=0"), analyst(), null).andExpect(status().isBadRequest());
        call(get("/api/ops/transactions?status=NOPE"), analyst(), null).andExpect(status().isBadRequest());
        var first = read(call(get("/api/ops/transactions?size=2&page=0"), analyst(), null).andExpect(status().isOk()));
        var second = read(call(get("/api/ops/transactions?size=2&page=1"), analyst(), null).andExpect(status().isOk()));
        assertThat(first.path("items").size()).isEqualTo(2);
        assertThat(first.path("counts").path("ALL").asLong()).isEqualTo(first.path("total").asLong());
        assertThat(first.path("total").asLong()).isGreaterThan(2);
        assertThat(second.path("items").get(0).path("id").asText()).isNotEqualTo(first.path("items").get(0).path("id").asText());
        assertThat(first.path("items").get(0).path("createdAt").asText()).isGreaterThanOrEqualTo(second.path("items").get(0).path("createdAt").asText());
        call(get("/api/ops/overview?days=7"), ana(), null).andExpect(status().isForbidden());
    }

    @Test
    void gruposDeAtencaoEAguardandoBatemComOsEstados() throws Exception {
        var token = analyst();
        var all = read(call(get("/api/ops/transactions?size=100"), token, null).andExpect(status().isOk()));
        var counts = all.path("counts");
        long attention = counts.path("INTERVENTION").asLong() + counts.path("CONTEXT_REQUIRED").asLong()
                + counts.path("ANALYZING").asLong() + counts.path("BLOCKED").asLong();
        long waiting = counts.path("CONTEXT_REQUIRED").asLong() + counts.path("INTERVENTION").asLong();
        assertThat(counts.path("ATENCAO").asLong()).isEqualTo(attention);
        assertThat(counts.path("AGUARDANDO").asLong()).isEqualTo(waiting);

        var atencao = read(call(get("/api/ops/transactions?status=ATENCAO&size=100"), token, null).andExpect(status().isOk()));
        assertThat(atencao.path("total").asLong()).isEqualTo(attention);
        atencao.path("items").forEach(item -> assertThat(item.path("status").asText())
                .isIn("INTERVENTION", "CONTEXT_REQUIRED", "ANALYZING", "BLOCKED"));

        var aguardando = read(call(get("/api/ops/transactions?status=AGUARDANDO&size=100"), token, null).andExpect(status().isOk()));
        assertThat(aguardando.path("total").asLong()).isEqualTo(waiting);
        aguardando.path("items").forEach(item -> assertThat(item.path("status").asText())
                .isIn("CONTEXT_REQUIRED", "INTERVENTION"));
        assertThat(aguardando.path("counts").path("ALL").asLong()).isEqualTo(counts.path("ALL").asLong());
    }

    @Test
    void auditoriaPaginaFiltraOrdenaEProtege() throws Exception {
        var token = analyst();
        for (String title : new String[]{"Aviso alfa", "Aviso beta", "Aviso gama"}) {
            call(post("/api/ops/alerts"), token, Map.of("customerId", "CUST-001", "title", title, "body", "texto do aviso")).andExpect(status().isCreated());
        }
        call(get("/api/ops/audit"), null, null).andExpect(status().isUnauthorized());
        call(get("/api/ops/audit"), ana(), null).andExpect(status().isForbidden());
        call(get("/api/ops/audit?size=101"), token, null).andExpect(status().isBadRequest());
        call(get("/api/ops/audit?page=-1"), token, null).andExpect(status().isBadRequest());

        var first = read(call(get("/api/ops/audit?page=0&size=2&action=ALERT_SENT"), token, null).andExpect(status().isOk()));
        assertThat(first.path("total").asLong()).isGreaterThanOrEqualTo(3);
        assertThat(first.path("items").size()).isEqualTo(2);
        assertThat(first.path("items").get(0).path("id").asLong()).isGreaterThan(first.path("items").get(1).path("id").asLong());
        assertThat(first.path("items").get(0).path("action").asText()).isEqualTo("ALERT_SENT");

        var second = read(call(get("/api/ops/audit?page=1&size=2&action=ALERT_SENT"), token, null).andExpect(status().isOk()));
        assertThat(second.path("items").get(0).path("id").asLong()).isLessThan(first.path("items").get(1).path("id").asLong());

        var found = read(call(get("/api/ops/audit?q=beta"), token, null).andExpect(status().isOk()));
        assertThat(found.path("items").get(0).path("detail").asText()).isEqualTo("Aviso beta");

        call(get("/api/ops/audit/export?action=ALERT_SENT"), ana(), null).andExpect(status().isForbidden());
        call(get("/api/ops/audit/export?q=alfa"), token, null)
                .andExpect(status().isOk())
                .andExpect(header().string("X-Export-Truncated", "false"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("Aviso alfa").contains("quando;quem;acao"));
    }

    @Test
    void configuracaoDoMotorRejeitaChaveDesconhecidaEAuditaAValida() throws Exception {
        var token = analyst();
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/ops/engine/config"), token,
                Map.of("key", "peso-inventado", "enabled", false)).andExpect(status().isBadRequest());
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/ops/engine/config"), ana(),
                Map.of("key", "script-mirror", "enabled", false)).andExpect(status().isForbidden());
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/ops/engine/config"), token,
                Map.of("key", "script-mirror", "enabled", false)).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.flags['script-mirror']").value(false));
        var audit = read(call(get("/api/ops/audit?action=ENGINE_SETTING&q=script-mirror"), token, null).andExpect(status().isOk()));
        assertThat(audit.path("items").get(0).path("detail").asText()).isEqualTo("false");
    }
}
