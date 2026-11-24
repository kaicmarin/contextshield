package com.contextshield.core.service;

import com.contextshield.core.config.AppProperties;
import com.contextshield.core.config.JwtService;
import com.contextshield.core.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * Chamada HTTP ao microsserviço risk-engine.
 * Comunicação síncrona com timeout curto e uma nova tentativa. Se o motor não responder,
 * a compra NÃO é aprovada: fica em análise e a API devolve 503 (falha segura).
 */
@Component
public class RiskEngineClient {

    private static final Logger log = LoggerFactory.getLogger(RiskEngineClient.class);

    private final RestClient http;
    private final JwtService jwt;

    public RiskEngineClient(AppProperties props, JwtService jwt) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.riskEngine().timeoutMs());
        factory.setReadTimeout(props.riskEngine().timeoutMs());
        this.http = RestClient.builder().baseUrl(props.riskEngine().url()).requestFactory(factory).build();
        this.jwt = jwt;
    }

    public static class EngineUnavailable extends ApiException {
        public EngineUnavailable() {
            super(HttpStatus.SERVICE_UNAVAILABLE,
                    "O motor de risco não respondeu. A compra não foi aprovada e nada foi cobrado; tente de novo em instantes.");
        }
    }

    public JsonNode assess(Map<String, Object> input) {
        RestClientException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            long t0 = System.nanoTime();
            try {
                JsonNode body = http.post().uri("/api/v1/assessments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt.serviceToken())
                        .body(input)
                        .retrieve()
                        .body(JsonNode.class);
                if (body instanceof ObjectNode o) o.put("roundTripMs", Math.round((System.nanoTime() - t0) / 10_000.0) / 100.0);
                return body;
            } catch (HttpClientErrorException e) {
                log.error("risk-engine recusou o contexto: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
                throw new ApiException(HttpStatus.BAD_GATEWAY, "O motor de risco recusou os dados desta compra.");
            } catch (RestClientException e) {
                last = e;
                log.warn("risk-engine indisponível (tentativa {}): {}", attempt, e.getMessage());
                try {
                    Thread.sleep(150L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        log.error("risk-engine fora do ar", last);
        throw new EngineUnavailable();
    }

    public JsonNode model() {
        try {
            return http.get().uri("/api/v1/model").retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw new EngineUnavailable();
        }
    }
}
