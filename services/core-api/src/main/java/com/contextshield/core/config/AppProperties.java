package com.contextshield.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "contextshield")
public record AppProperties(Jwt jwt, RiskEngine riskEngine, List<String> corsOrigins, Seed seed, Check check,
                             Service ml, Service threat, Service investigation) {
    public AppProperties {
        if (check == null) check = new Check(40);
        if (ml == null) ml = new Service("", 800);
        if (threat == null) threat = new Service("", 800);
        if (investigation == null) investigation = new Service("", 800);
    }

    public record Jwt(String secret, long ttlMinutes) {}

    public record RiskEngine(String url, int timeoutMs) {}

    public record Seed(boolean enabled, String demoPassword, boolean anchorToday) {}

    public record Check(int perMinute) {}

    public record Service(String url, int timeoutMs) {}
}
