package com.contextshield.mlservice;

import com.contextshield.ml.Features;
import com.contextshield.ml.SavedModel;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PredictionApiTest {
    private static final String SECRET = "dev-only-secret-change-me-0123456789abcdef";

    @Autowired TestRestTemplate http;
    @Value("${contextshield.models-dir}") String modelsDir;

    @Test
    void publishedModelMatchesTheFileAndDoesNotDecide() throws Exception {
        Path file = Path.of(modelsDir).resolve("logistic-regression.ser");
        SavedModel direct = SavedModel.load(file);
        Map<String, Object> checkout = checkout();
        SavedModel.Prediction expected = direct.predict(Features.vector(checkout));
        ResponseEntity<Map> response = http.exchange("/internal/v1/predictions", HttpMethod.POST,
                new HttpEntity<>(Map.of("modelFile", "logistic-regression.ser", "checkout", checkout, "affectsDecision", true), service()),
                Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected.label(), response.getBody().get("prediction"));
        assertEquals(expected.fraudProbability(), ((Number) response.getBody().get("probability")).doubleValue(), 0.0001);
        assertEquals(false, response.getBody().get("affectsDecision"));
    }

    @Test
    void missingTokenIsRejectedAndTrainingStaysOutOfTheRequest() {
        ResponseEntity<String> denied = http.postForEntity("/internal/v1/predictions", Map.of(), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, denied.getStatusCode());
        ResponseEntity<String> training = http.exchange("/internal/v1/training", HttpMethod.POST,
                new HttpEntity<>(Map.of(), service()), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, training.getStatusCode());
        ResponseEntity<String> reserved = http.exchange("/internal/v1/training", HttpMethod.POST,
                new HttpEntity<>(Map.of("examples", 200, "seed", 9901), service()), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, reserved.getStatusCode());
        ResponseEntity<String> escape = http.exchange("/internal/v1/predictions", HttpMethod.POST,
                new HttpEntity<>(Map.of("modelFile", "../logistic-regression.ser", "checkout", checkout()), service()),
                String.class);
        assertEquals(HttpStatus.BAD_REQUEST, escape.getStatusCode());
        assertFalse(escape.getBody().contains("APPROVE"));
    }

    private static HttpHeaders service() {
        Instant now = Instant.now();
        String token = Jwts.builder().subject("core-api").claim("role", "SERVICE").claim("org", 1)
                .claim("scope", "predict,read,train")
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(5, ChronoUnit.MINUTES)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static Map<String, Object> checkout() {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("id", "C1");
        customer.put("city", "Campinas");
        customer.put("since", "2020-01-01");
        customer.put("avgTicket90d", 200);
        customer.put("purchases90d", 8);
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("trusted", true);
        device.put("location", "Campinas, SP");
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("kind", "home");
        address.put("customerIds", List.of("C1"));
        Map<String, Object> seller = new LinkedHashMap<>();
        seller.put("since", "2019-01-01");
        seller.put("claimsRate", 0.4);
        seller.put("rating", 4.8);
        seller.put("tier", "ouro");
        Map<String, Object> checkout = new LinkedHashMap<>();
        checkout.put("amount", 150);
        checkout.put("at", "2026-08-10T15:00:00");
        checkout.put("customer", customer);
        checkout.put("device", device);
        checkout.put("address", address);
        checkout.put("addressUsedBefore", true);
        checkout.put("sellers", List.of(seller));
        checkout.put("declaredOrigin", "self");
        checkout.put("relations", List.of());
        return checkout;
    }
}
