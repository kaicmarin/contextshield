package com.contextshield.core.api;

import com.contextshield.core.TestSupport;
import com.contextshield.core.domain.AppUser;
import com.contextshield.core.repo.AppUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PasswordSecurityTest extends TestSupport {

    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder encoder;

    @Test
    void usuariosDeDemonstracaoTemSenhasDiferentes() {
        AppUser ana = users.findByEmailIgnoreCase("ana.martins@correio.example").orElseThrow();
        AppUser admin = users.findByEmailIgnoreCase("admin@contextshield.example").orElseThrow();
        assertThat(ana.getPasswordHash()).isNotEqualTo(admin.getPasswordHash());
        assertThat(encoder.matches(passwordFor("ana.martins@correio.example"), ana.getPasswordHash())).isTrue();
        assertThat(encoder.matches(passwordFor("admin@contextshield.example"), admin.getPasswordHash())).isTrue();
    }

    @Test
    void senhaDeUmUsuarioNaoAutenticaOutro() throws Exception {
        call(post("/api/auth/login"), null,
                Map.of("email", "ana.martins@correio.example", "password", passwordFor("admin@contextshield.example")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void perfisRespeitamLimitesDeAcesso() throws Exception {
        call(get("/api/admin/users"), ana(), null).andExpect(status().isForbidden());
        call(put("/api/ops/engine/config"), analyst(), Map.of("key", "script-mirror", "enabled", false))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCriaUsuarioComSenhaPropria() throws Exception {
        String token = admin();
        String email = "analista.novo." + System.nanoTime() + "@contextshield.example";
        var body = Map.of("email", email, "name", "Novo Analista", "password", "CsIt-New-Analyst1", "role", "ANALYST");
        call(post("/api/admin/users"), token, body).andExpect(status().isCreated());
        AppUser created = users.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(encoder.matches("CsIt-New-Analyst1", created.getPasswordHash())).isTrue();
        assertThat(encoder.matches(passwordFor("admin@contextshield.example"), created.getPasswordHash())).isFalse();
    }

    @Test
    void trocaSenhaInvalidaTokenAntigo() throws Exception {
        var reg = Map.of(
                "name", "Pwd Troca Teste",
                "email", "pwd.troca@correio.example",
                "password", "CsIt-Reg-User01",
                "city", "São Paulo",
                "addressLine", "Rua Teste, 10",
                "district", "Centro",
                "zip", "01000-000");
        String oldToken = read(call(post("/api/auth/register"), null, reg).andExpect(status().isCreated())).path("token").asText();
        String current = "CsIt-Reg-User01";
        String nova = "CsIt-Reg-User02";
        var r = read(call(post("/api/me/password"), oldToken, Map.of(
                "currentPassword", current,
                "newPassword", nova,
                "confirmPassword", nova)).andExpect(status().isOk()));
        assertThat(r.path("token").asText()).isNotBlank();
        call(get("/api/me"), oldToken, null).andExpect(status().isUnauthorized());
        call(post("/api/auth/login"), null, Map.of("email", "pwd.troca@correio.example", "password", current))
                .andExpect(status().isUnauthorized());
        call(post("/api/auth/login"), null, Map.of("email", "pwd.troca@correio.example", "password", nova))
                .andExpect(status().isOk());
    }

    @Test
    void respostasNaoExpõemSenhas() throws Exception {
        var r = read(call(post("/api/auth/login"), null,
                Map.of("email", "analista@contextshield.example", "password", passwordFor("analista@contextshield.example")))
                .andExpect(status().isOk()));
        String raw = r.toString();
        assertThat(raw).doesNotContain(passwordFor("analista@contextshield.example"));
        call(get("/api/admin/users"), admin(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].password").doesNotExist());
    }

    @Test
    void trocaSenhaRejeitaReutilizacaoEConfirmacaoIncorreta() throws Exception {
        String token = login("carlos.menezes@correio.example");
        String current = passwordFor("carlos.menezes@correio.example");
        call(post("/api/me/password"), token, Map.of(
                "currentPassword", current,
                "newPassword", current + "X",
                "confirmPassword", current + "Y"))
                .andExpect(status().isBadRequest());
        call(post("/api/me/password"), token, Map.of(
                "currentPassword", current,
                "newPassword", current,
                "confirmPassword", current))
                .andExpect(status().isConflict());
    }
}