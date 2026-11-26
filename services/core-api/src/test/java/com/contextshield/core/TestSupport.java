package com.contextshield.core;

import com.contextshield.core.config.AppProperties;
import com.contextshield.core.config.JwtService;
import com.contextshield.core.service.SeedCredentialLoader;
import com.contextshield.core.service.SeedService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Base dos testes de integração: sobe a core-api inteira com H2 (modo PostgreSQL), aplica as migrações
 * do Flyway, carrega a massa da demonstração e usa o motor de risco real em memória.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(TestSupport.EngineConfig.class)
public abstract class TestSupport {

    @TestConfiguration
    static class EngineConfig {
        @Bean
        @Primary
        InProcessEngine inProcessEngine(AppProperties props, JwtService jwt) {
            return new InProcessEngine(props, jwt);
        }
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected SeedService seed;
    @Autowired protected SeedCredentialLoader seedCredentials;
    @Autowired protected InProcessEngine engine;

    private static boolean seeded = false;

    @BeforeEach
    void seedOnce() {
        engine.down = false;
        synchronized (TestSupport.class) {
            if (!seeded) {
                seed.seedReference();
                seeded = true;
            }
            // cada teste parte da mesma história; incidentes de um teste não vazam para outro
            seed.resetDynamicData();
        }
    }

    protected String login(String email) throws Exception {
        String pw = seedCredentials.passwordFor(email);
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + pw + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("token").asText();
    }

    protected String passwordFor(String email) {
        return seedCredentials.passwordFor(email);
    }

    protected String ana() throws Exception { return login("ana.martins@correio.example"); }

    protected String analyst() throws Exception { return login("analista@contextshield.example"); }

    protected String admin() throws Exception { return login("admin@contextshield.example"); }

    protected ResultActions call(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
        return mvc.perform(req);
    }

    protected JsonNode read(ResultActions r) throws Exception {
        String s = r.andReturn().getResponse().getContentAsString();
        return s.isEmpty() ? json.createObjectNode() : json.readTree(s);
    }
}
