package com.contextshield.core.service;

import com.contextshield.core.config.AppProperties;
import com.contextshield.core.config.JwtService;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Chamada ao ML Service. A resposta é sombra: não autoriza a compra. */
@Component
public class MlClient {
    private final RestClient http;
    private final JwtService jwt;

    public MlClient(AppProperties props, JwtService jwt) {
        this.jwt = jwt;
        if (props.ml().url() == null || props.ml().url().isBlank()) {
            this.http = null;
            return;
        }
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.ml().timeoutMs());
        factory.setReadTimeout(props.ml().timeoutMs());
        this.http = RestClient.builder().baseUrl(props.ml().url()).requestFactory(factory).build();
    }

    public boolean enabled() {
        return http != null;
    }

    public Map<String, Object> predict(String modelFile, Map<String, Object> checkout, boolean contextual, long organizationId) {
        Map body = http.post().uri("/internal/v1/predictions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + jwt.serviceToken(organizationId))
                .body(Map.of("modelFile", modelFile, "checkout", checkout, "contextual", contextual))
                .retrieve()
                .body(Map.class);
        return body == null ? Map.of() : body;
    }
}
