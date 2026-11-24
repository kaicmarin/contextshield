package com.contextshield.core.api;

import com.contextshield.core.TestSupport;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Segurança: login com JWT, rotas protegidas, papéis e cadastro. */
class AuthApiTest extends TestSupport {

    @Test
    void loginDevolveTokenJwtComPapel() throws Exception {
        var r = read(call(post("/api/auth/login"), null, Map.of("email", "ana.martins@correio.example", "password", "contexto"))
                .andExpect(status().isOk()));
        assertThat(r.path("token").asText()).contains(".");
        assertThat(r.path("user").path("role").asText()).isEqualTo("CUSTOMER");
        assertThat(r.path("user").path("customerId").asText()).isEqualTo("CUST-001");
    }

    @Test
    void senhaErradaDevolve401() throws Exception {
        call(post("/api/auth/login"), null, Map.of("email", "ana.martins@correio.example", "password", "errada"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("E-mail ou senha incorretos"));
    }

    @Test
    void rotaProtegidaSemTokenDevolve401() throws Exception {
        call(get("/api/transactions"), null, null).andExpect(status().isUnauthorized());
        call(get("/api/transactions"), null, null).andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void tokenAdulteradoDevolve401() throws Exception {
        String token = ana();
        call(get("/api/me"), token.substring(0, token.length() - 3) + "abc", null).andExpect(status().isUnauthorized());
    }

    @Test
    void clienteNaoEntraNoPainelDoAnalista() throws Exception {
        call(get("/api/ops/overview"), ana(), null).andExpect(status().isForbidden());
        call(get("/api/admin/users"), analyst(), null).andExpect(status().isForbidden());
        call(get("/api/ops/overview"), analyst(), null).andExpect(status().isOk());
    }

    @Test
    void rotasPublicasNaoPedemToken() throws Exception {
        call(get("/api/catalog/products"), null, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(28));
        call(get("/api/catalog/scenarios"), null, null).andExpect(status().isOk());
        call(get("/actuator/health"), null, null).andExpect(status().isOk());
    }

    @Test
    void cadastroCriaClienteEImpedeEmailRepetido() throws Exception {
        var body = Map.of("name", "Eric Teste", "email", "eric.teste@correio.example", "password", "senha-forte-1",
                "city", "São Paulo", "addressLine", "Rua do Teste, 100", "district", "Centro", "zip", "01000-000");
        var r = read(call(post("/api/auth/register"), null, body).andExpect(status().isCreated()));
        String token = r.path("token").asText();
        call(get("/api/me"), token, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.purchases90d").value(0))
                .andExpect(jsonPath("$.addresses[0].line1").value("Rua do Teste, 100"));
        call(post("/api/auth/register"), null, body).andExpect(status().isConflict());
    }

    @Test
    void cadastroInvalidoListaOsCampos() throws Exception {
        call(post("/api/auth/register"), null, Map.of("name", "E", "email", "nao-e-email", "password", "123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.email").exists())
                .andExpect(jsonPath("$.fields.password").exists())
                .andExpect(jsonPath("$.fields.city").exists());
    }
}
