package com.contextshield.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "contextshield")
public record AppProperties(Jwt jwt, RiskEngine riskEngine, List<String> corsOrigins, Seed seed, Check check) {
    public AppProperties {
        if (check == null) check = new Check(40);
    }

    public record Jwt(String secret, long ttlMinutes) {}

    public record RiskEngine(String url, int timeoutMs) {}

    public record Seed(boolean enabled, String demoPassword, boolean anchorToday) {}

    public record Check(int perMinute) {}
}
