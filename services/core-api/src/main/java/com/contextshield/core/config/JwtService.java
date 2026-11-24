package com.contextshield.core.config;

import com.contextshield.core.domain.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/** Emite e valida os JWT (HS256). O mesmo segredo assina o token de serviço enviado ao risk-engine. */
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlMinutes;

    public JwtService(AppProperties props) {
        if (props.jwt().secret() == null || props.jwt().secret().length() < 32)
            throw new IllegalStateException("JWT_SECRET precisa ter pelo menos 32 caracteres");
        if ("dev-only-secret-change-me-0123456789abcdef".equals(props.jwt().secret()))
            org.slf4j.LoggerFactory.getLogger(JwtService.class)
                    .warn("JWT_SECRET está no valor de desenvolvimento. Defina outro segredo, com 32 caracteres ou mais, antes de publicar.");
        this.key = Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8));
        this.ttlMinutes = props.jwt().ttlMinutes();
    }

    public record Issued(String token, Instant expiresAt) {}

    public Issued issue(AppUser user) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttlMinutes, ChronoUnit.MINUTES);
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", user.getRole().name());
        claims.put("name", user.getName());
        if (user.getCustomerId() != null) claims.put("customerId", user.getCustomerId());
        String token = Jwts.builder().subject(user.getEmail()).claims(claims)
                .issuedAt(Date.from(now)).expiration(Date.from(exp)).issuer("contextshield-core").signWith(key).compact();
        return new Issued(token, exp);
    }

    /** Token curto que a core-api usa para chamar o risk-engine. */
    public String serviceToken() {
        Instant now = Instant.now();
        return Jwts.builder().subject("core-api").claim("role", "SERVICE")
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(5, ChronoUnit.MINUTES)))
                .issuer("contextshield-core").signWith(key).compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
