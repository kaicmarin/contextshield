package com.contextshield.threat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IndicatorApiTest {
    private static final String SECRET = "dev-only-secret-change-me-0123456789abcdef";

    @Autowired TestRestTemplate http;

    @Test
    void reportStaysUnverifiedUntilAnExplicitReviewAndDoesNotTrain() {
        ResponseEntity<Map> created = http.exchange("/internal/v1/indicators", HttpMethod.POST,
                new HttpEntity<>(Map.of("kind", "url", "value", "http://169.254.169.254/latest"), token(1)), Map.class);
        assertEquals(HttpStatus.OK, created.getStatusCode());
        assertEquals("UNVERIFIED", created.getBody().get("status"));
        assertEquals(false, created.getBody().get("trainingLabel"));
        assertEquals(false, created.getBody().get("fetchesUrl"));
        String id = String.valueOf(created.getBody().get("id"));

        ResponseEntity<String> otherOrg = http.exchange("/internal/v1/indicators/" + id + "/review", HttpMethod.POST,
                new HttpEntity<>(Map.of("status", "CONFIRMED", "note", "visto"), token(2)), String.class);
        assertEquals(HttpStatus.NOT_FOUND, otherOrg.getStatusCode());

        ResponseEntity<String> jump = http.exchange("/internal/v1/indicators/" + id + "/review", HttpMethod.POST,
                new HttpEntity<>(Map.of("status", "CONFIRMED", "note", "cedo demais"), token(1)), String.class);
        assertEquals(HttpStatus.CONFLICT, jump.getStatusCode());
        http.exchange("/internal/v1/indicators/" + id + "/review", HttpMethod.POST,
                new HttpEntity<>(Map.of("status", "IN_REVIEW", "note", "em análise"), token(1)), Map.class);
        ResponseEntity<Map> confirmed = http.exchange("/internal/v1/indicators/" + id + "/review", HttpMethod.POST,
                new HttpEntity<>(Map.of("status", "CONFIRMED", "note", "analista conferiu o relato", "visibility", "SHARED"), token(1)),
                Map.class);
        assertEquals("CONFIRMED", confirmed.getBody().get("status"));
        assertEquals(false, confirmed.getBody().get("trainingLabel"));
        assertEquals("analista@contextshield.example", confirmed.getBody().get("reviewedBy"));

        ResponseEntity<List<Map<String, Object>>> shared = http.exchange("/internal/v1/indicators/shared", HttpMethod.GET,
                new HttpEntity<>(token(2)), new ParameterizedTypeReference<>() {});
        assertEquals(1, shared.getBody().size());
        assertFalse(shared.getBody().get(0).containsKey("display"));
        assertFalse(shared.getBody().get(0).containsKey("valueHash"));
        ResponseEntity<Map> guessed = http.exchange("/internal/v1/indicators/match", HttpMethod.POST,
                new HttpEntity<>(Map.of("kind", "url", "value", "http://169.254.169.254/latest", "sharedOnly", true), token(2)), Map.class);
        assertEquals(true, guessed.getBody().get("matched"));
        assertFalse(guessed.getBody().containsKey("valueHash"));
        assertTrue(((Number) guessed.getBody().get("reputation")).intValue() >= 1);
    }

    @Test
    void bodyOrganizationIsIgnored() {
        ResponseEntity<Map> created = http.exchange("/internal/v1/indicators", HttpMethod.POST,
                new HttpEntity<>(Map.of("kind", "phone", "value", "11999990000", "organizationId", 99), token(1)), Map.class);
        assertEquals(HttpStatus.OK, created.getStatusCode());
        ResponseEntity<List<Map<String, Object>>> mine = http.exchange("/internal/v1/indicators", HttpMethod.GET,
                new HttpEntity<>(token(1)), new ParameterizedTypeReference<>() {});
        ResponseEntity<List<Map<String, Object>>> other = http.exchange("/internal/v1/indicators", HttpMethod.GET,
                new HttpEntity<>(token(99)), new ParameterizedTypeReference<>() {});
        assertEquals(1, mine.getBody().stream().filter(row -> "phone".equals(row.get("kind"))).count());
        assertEquals(0, other.getBody().size());
    }

    private static HttpHeaders token(long org) {
        Instant now = Instant.now();
        String token = Jwts.builder().subject("analista@contextshield.example").claim("role", "SERVICE").claim("org", org)
                .claim("scope", "review,read,lookup")
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(5, ChronoUnit.MINUTES)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
