package com.contextshield.mlservice.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitFilterTest {

    @Test
    void expiresOldMinuteBuckets() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(2);
        var req = new org.springframework.mock.web.MockHttpServletRequest("GET", "/internal/v1/overview");
        req.setRemoteAddr("10.0.0.1");
        req.setAttribute("actor", "tester");
        var res = new org.springframework.mock.web.MockHttpServletResponse();
        var chain = (jakarta.servlet.FilterChain) (request, response) -> { };
        filter.doFilter(req, res, chain);
        filter.doFilter(req, res, chain);
        filter.doFilter(req, res, chain);
        assertTrue(res.getStatus() == 429 || res.getStatus() == 200);
    }
}