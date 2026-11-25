package com.contextshield.investigation;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CaseApiTest {
    private static final String SECRET = "dev-only-secret-change-me-0123456789abcdef";

    @Autowired TestRestTemplate http;

    @Test
    void confirmedFraudDoesNotBecomeATrainingLabel() {
        ResponseEntity<Map> created = http.exchange("/internal/v1/cases", HttpMethod.POST,
                new HttpEntity<>(Map.of("sourceType", "transaction", "sourceId", "TX-1", "actor", "analista@contextshield.example",
                        "organizationId", 9), token(1)), Map.class);
        assertEquals(HttpStatus.OK, created.getStatusCode());
        assertEquals("OPEN", created.getBody().get("state"));
        String id = String.valueOf(created.getBody().get("id"));

        ResponseEntity<String> foreign = http.exchange("/internal/v1/cases/" + id + "/transition", HttpMethod.POST,
                new HttpEntity<>(Map.of("state", "UNDER_REVIEW", "actor", "outro"), token(2)), String.class);
        assertEquals(HttpStatus.NOT_FOUND, foreign.getStatusCode());

        ResponseEntity<Map> assigned = http.exchange("/internal/v1/cases/" + id + "/assign", HttpMethod.POST,
                new HttpEntity<>(Map.of("actor", "outra-pessoa"), token(1)), Map.class);
        assertEquals("UNDER_REVIEW", assigned.getBody().get("state"));
        assertEquals("analista@contextshield.example", assigned.getBody().get("assignee"));
        http.exchange("/internal/v1/cases/" + id + "/evidence", HttpMethod.POST,
                new HttpEntity<>(Map.of("kind", "NOTE", "body", "o roteiro coincide com o destino"), token(1)), Map.class);
        ResponseEntity<Map> confirmed = http.exchange("/internal/v1/cases/" + id + "/transition", HttpMethod.POST,
                new HttpEntity<>(Map.of("state", "CONFIRMED_FRAUD", "note", "há script e destino coincidentes",
                        "actor", "outra-pessoa"), token(1)), Map.class);
        assertEquals(HttpStatus.OK, confirmed.getStatusCode());
        assertEquals("CONFIRMED_FRAUD", confirmed.getBody().get("state"));
        assertEquals(false, confirmed.getBody().get("trainingLabel"));
        assertEquals(false, confirmed.getBody().get("publishesModel"));
        assertEquals("analista@contextshield.example", confirmed.getBody().get("actor"));
        assertEquals(1, ((java.util.List<?>) confirmed.getBody().get("evidence")).size());
    }

    @Test
    void denialWithoutEvidenceStaysOpenUntilReviewed() {
        ResponseEntity<Map> created = http.exchange("/internal/v1/cases", HttpMethod.POST,
                new HttpEntity<>(Map.of("sourceType", "transaction", "sourceId", "TX-2", "actor", "analista@contextshield.example"), token(1)),
                Map.class);
        String id = String.valueOf(created.getBody().get("id"));
        ResponseEntity<String> jump = http.exchange("/internal/v1/cases/" + id + "/transition", HttpMethod.POST,
                new HttpEntity<>(Map.of("state", "CONFIRMED_FRAUD", "note", "sem revisão"), token(1)), String.class);
        assertEquals(HttpStatus.CONFLICT, jump.getStatusCode());
    }

    private static HttpHeaders token(long org) {
        Instant now = Instant.now();
        String token = Jwts.builder().subject("analista@contextshield.example").claim("role", "SERVICE").claim("org", org)
                .claim("scope", "investigate,read,report")
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(5, ChronoUnit.MINUTES)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
