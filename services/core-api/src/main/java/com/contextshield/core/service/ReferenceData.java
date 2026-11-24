package com.contextshield.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Massa de dados fictícia da demonstração (seed/reference.json): clientes, aparelhos, endereços,
 * loja, vendedores, produtos, cenários e o histórico de compras e relatos de setembro de 2026.
 */
@Component
public class ReferenceData {
    private final JsonNode root;

    public ReferenceData(ObjectMapper mapper) throws IOException {
        try (InputStream in = new ClassPathResource("seed/reference.json").getInputStream()) {
            this.root = mapper.readTree(in);
        }
    }

    public JsonNode get(String key) {
        return root.path(key);
    }

    public Optional<JsonNode> scenario(String id) {
        for (JsonNode s : root.path("scenarios")) if (s.path("id").asText().equals(id)) return Optional.of(s);
        return Optional.empty();
    }

    /** As mensagens que o golpista mandou no cenário, em um texto só (é o que o motor lê como roteiro). */
    public static String coachScript(JsonNode scenario) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode m : scenario.path("outside").path("messages")) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(m.path("text").asText());
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
