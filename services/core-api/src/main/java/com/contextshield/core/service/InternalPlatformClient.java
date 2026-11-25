package com.contextshield.core.service;

import com.contextshield.core.config.AppProperties;
import com.contextshield.core.config.JwtService;
import com.contextshield.core.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/** A core-api é a porta. Threat e Investigation não recebem o token do navegador. */
@Component
public class InternalPlatformClient {
    private final RestClient threat;
    private final RestClient investigation;
    private final JwtService jwt;

    public InternalPlatformClient(AppProperties props, JwtService jwt) {
        this.jwt = jwt;
        this.threat = client(props.threat());
        this.investigation = client(props.investigation());
    }

    public boolean threatEnabled() {
        return threat != null;
    }

    public boolean investigationEnabled() {
        return investigation != null;
    }

    public Object threat(String method, String path, long organizationId, String actor, String scope, Object body) {
        return call(threat, "inteligência", method, path, organizationId, actor, scope, body);
    }

    public Object investigation(String method, String path, long organizationId, String actor, String scope, Object body) {
        return call(investigation, "investigação", method, path, organizationId, actor, scope, body);
    }

    /** Só indicador confirmado entra como evidência. Pendente não confirma o valor. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> matchIndicator(long organizationId, String kind, String value, boolean sharedOnly) {
        if (!threatEnabled()) return Map.of("matched", false);
        Object body = call(threat, "inteligência", "POST", "/internal/v1/indicators/match", organizationId, "core-api", "lookup",
                Map.of("kind", kind, "value", value, "sharedOnly", sharedOnly));
        return body instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of("matched", false);
    }

    private Object call(RestClient http, String name, String method, String path, long organizationId, String actor, String scope, Object body) {
        if (http == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "O serviço de " + name + " não está configurado.");
        }
        try {
            var spec = "POST".equals(method) ? http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body == null ? Map.of() : body)
                    : http.get().uri(path);
            return spec.header("Authorization", "Bearer " + jwt.serviceToken(organizationId, actor, scope)).retrieve().body(Object.class);
        } catch (RestClientResponseException e) {
            String detail = e.getResponseBodyAsString();
            if (detail != null && detail.length() > 180) detail = detail.substring(0, 180);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "O serviço de " + name + " respondeu " + e.getStatusCode().value()
                    + (detail == null || detail.isBlank() ? "." : ": " + detail));
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "O serviço de " + name + " não respondeu. A compra não muda por causa disso.");
        }
    }

    private static RestClient client(AppProperties.Service service) {
        if (service == null || service.url() == null || service.url().isBlank()) return null;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(service.timeoutMs());
        factory.setReadTimeout(service.timeoutMs());
        return RestClient.builder().baseUrl(service.url()).requestFactory(factory).build();
    }
}
