package com.contextshield.risk.web;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** API do risk-engine: só aceita token de serviço, valida a entrada e expõe o catálogo. */
@SpringBootTest
@AutoConfigureMockMvc
class RiskApiTest {

    @Autowired MockMvc mvc;
    @Value("${contextshield.jwt.secret}") String secret;

    private String token(String role) {
        return Jwts.builder().subject("teste").claim("role", role).expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private String firstCase() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var cases = mapper.readTree(new ClassPathResource("parity-cases.json").getInputStream());
        return cases.get(0).get("input").toString();
    }

    @Test
    void semTokenDevolve401() throws Exception {
        mvc.perform(post("/api/v1/assessments").contentType(MediaType.APPLICATION_JSON).content(firstCase()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void clienteNaoChamaOMotorDireto() throws Exception {
        mvc.perform(post("/api/v1/assessments").header("Authorization", "Bearer " + token("CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON).content(firstCase())).andExpect(status().isForbidden());
    }

    @Test
    void tokenDeServicoAvalia() throws Exception {
        mvc.perform(post("/api/v1/assessments").header("Authorization", "Bearer " + token("SERVICE"))
                        .contentType(MediaType.APPLICATION_JSON).content(firstCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").isNumber())
                .andExpect(jsonPath("$.decision").exists())
                .andExpect(jsonPath("$.certificate.status").exists());
    }

    @Test
    void contextoIncompletoDevolve400() throws Exception {
        mvc.perform(post("/api/v1/assessments").header("Authorization", "Bearer " + token("SERVICE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stage\":\"initial\",\"amount\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.amount").exists());
    }

    @Test
    void catalogoEhPublico() throws Exception {
        mvc.perform(get("/api/v1/model")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rules.length()").value(12))
                .andExpect(jsonPath("$.rules[?(@.id == 'R12')].outcome").value("REQUEST_CONTEXT"))
                .andExpect(jsonPath("$.signals.length()").value(32))
                .andExpect(jsonPath("$.signals[?(@.id == 'CONFIRMED_INDICATOR')].weight").value("+16"));
    }
}
