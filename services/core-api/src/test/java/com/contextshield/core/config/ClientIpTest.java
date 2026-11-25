package com.contextshield.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpTest {

    @Test
    void ignoraForwardedForSemProxyConfiavel() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("203.0.113.50");
        req.addHeader("X-Forwarded-For", "1.2.3.4, 10.0.0.1");
        assertThat(ClientIp.resolve(req, List.of())).isEqualTo("203.0.113.50");
    }

    @Test
    void usaForwardedForAtrasDeProxyConfiavel() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("X-Forwarded-For", "198.51.100.10, 10.0.0.1");
        assertThat(ClientIp.resolve(req, List.of("127.0.0.1"))).isEqualTo("198.51.100.10");
    }
}