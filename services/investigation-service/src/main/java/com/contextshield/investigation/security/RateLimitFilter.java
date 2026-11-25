package com.contextshield.investigation.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Teto por ator e minuto. Health fica de fora. Entradas expiradas são removidas. */
public class RateLimitFilter extends OncePerRequestFilter {
    private final int limit;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(int limit) {
        this.limit = limit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (path.startsWith("/actuator") || path.equals("/error")) {
            chain.doFilter(req, res);
            return;
        }
        Object actor = req.getAttribute("actor");
        String actorKey = actor == null ? req.getRemoteAddr() : actor.toString();
        long minute = System.currentTimeMillis() / 60_000;
        Bucket bucket = buckets.compute(actorKey, (key, existing) -> {
            if (existing == null || existing.minute != minute) {
                Bucket next = new Bucket();
                next.minute = minute;
                return next;
            }
            return existing;
        });
        if (bucket.count.incrementAndGet() > limit) {
            res.setStatus(429);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"status\":429,\"error\":\"Muitas chamadas internas neste minuto.\"}");
            return;
        }
        if (buckets.size() > 4096) {
            purgeExpired(minute);
        }
        chain.doFilter(req, res);
    }

    private void purgeExpired(long minute) {
        Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Bucket> entry = it.next();
            if (entry.getValue().minute < minute - 1) it.remove();
        }
    }

    private static final class Bucket {
        private long minute;
        private final AtomicInteger count = new AtomicInteger();
    }
}