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
        factory.setReadTimeout(Math.max(props.ml().timeoutMs(), 2500));
        this.http = RestClient.builder().baseUrl(props.ml().url()).requestFactory(factory).build();
    }

    public boolean enabled() {
        return http != null;
    }

    public Map<String, Object> predict(String featureSet, Map<String, Object> checkout, boolean contextual,
                                       long organizationId, String txId, String stage, String engineDecision) {
        return post("/internal/v1/predictions", organizationId, "core-api", "predict,read", Map.of(
                "featureSet", featureSet,
                "checkout", checkout,
                "contextual", contextual,
                "txId", txId,
                "stage", stage,
                "engineDecision", engineDecision == null ? "" : engineDecision));
    }

    public Map<String, Object> overview(long organizationId) {
        return get("/internal/v1/overview", organizationId, "core-api", "read");
    }

    public Map<String, Object> monitor(long organizationId) {
        return get("/internal/v1/monitor", organizationId, "core-api", "read");
    }

    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> shadow(long organizationId, String txId) {
        java.util.List body = http.get().uri(uri -> uri.path("/internal/v1/shadow").queryParam("txId", txId).build())
                .header("Authorization", "Bearer " + jwt.serviceToken(organizationId, "core-api", "read"))
                .retrieve().body(java.util.List.class);
        return body == null ? java.util.List.of() : body;
    }

    public Map<String, Object> experiment(long organizationId, long id) {
        return get("/internal/v1/experiments/" + id, organizationId, "core-api", "read");
    }

    public Map<String, Object> enqueue(long organizationId, String actor, int examples, long seed, Object confirmed) {
        return post("/internal/v1/experiments", organizationId, actor, "train,read", Map.of(
                "examples", examples, "seed", seed, "confirmed", confirmed == null ? java.util.List.of() : confirmed));
    }

    public Map<String, Object> approve(long organizationId, String actor, long id) {
        return post("/internal/v1/models/" + id + "/approve", organizationId, actor, "catalog,read", Map.of());
    }

    public Map<String, Object> reject(long organizationId, String actor, long id) {
        return post("/internal/v1/models/" + id + "/reject", organizationId, actor, "catalog,read", Map.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path, long organizationId, String actor, String scope) {
        Map body = http.get().uri(path)
                .header("Authorization", "Bearer " + jwt.serviceToken(organizationId, actor, scope))
                .retrieve().body(Map.class);
        return body == null ? Map.of() : body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, long organizationId, String actor, String scope, Object body) {
        Map response = http.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + jwt.serviceToken(organizationId, actor, scope))
                .body(body)
                .retrieve()
                .body(Map.class);
        return response == null ? Map.of() : response;
    }
}
