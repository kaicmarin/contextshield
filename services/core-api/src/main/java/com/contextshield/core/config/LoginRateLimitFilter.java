package com.contextshield.core.config;

import com.contextshield.core.service.CheckRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Teto por IP em POST /api/auth/login (complementa o limite por e-mail no AuthController). */
@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private final CheckRateLimiter byIp = new CheckRateLimiter();
    private final AppProperties props;
    private final ObjectMapper mapper;

    public LoginRateLimitFilter(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod()) && "/api/auth/login".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long now = System.currentTimeMillis();
        String ip = ClientIp.resolve(req, props.http().trustedProxies());
        if (!byIp.tryAcquire("ip:" + ip, props.login().perMinuteIp(), now)) {
            res.setStatus(429);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("timestamp", Instant.now().toString());
            body.put("status", 429);
            body.put("error", "Muitas tentativas de login. Aguarde um minuto e tente novamente.");
            body.put("path", req.getRequestURI());
            mapper.writeValue(res.getOutputStream(), body);
            return;
        }
        chain.doFilter(req, res);
    }
}