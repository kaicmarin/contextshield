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

/** Teto por IP em POST /api/check. Não registra o corpo da mensagem. */
@Component
public class CheckRateLimitFilter extends OncePerRequestFilter {

    private final CheckRateLimiter limiter = new CheckRateLimiter();
    private final AppProperties props;
    private final ObjectMapper mapper;

    public CheckRateLimitFilter(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod()) && "/api/check".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        int limit = props.check().perMinute();
        if (!limiter.tryAcquire(ClientIp.resolve(req, props.check().trustedProxies()), limit, System.currentTimeMillis())) {
            res.setStatus(429);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("timestamp", Instant.now().toString());
            body.put("status", 429);
            body.put("error", "Muitas consultas em pouco tempo. Espere um minuto e tente de novo.");
            body.put("path", req.getRequestURI());
            mapper.writeValue(res.getOutputStream(), body);
            return;
        }
        chain.doFilter(req, res);
    }

}
