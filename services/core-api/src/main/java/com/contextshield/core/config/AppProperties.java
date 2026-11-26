package com.contextshield.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "contextshield")
public record AppProperties(Jwt jwt, RiskEngine riskEngine, List<String> corsOrigins, Seed seed, Check check, Login login,
                             PasswordChange passwordChange, Http http, Service ml, Service threat, Service investigation) {
    public AppProperties {
        if (check == null) check = new Check(40, List.of());
        if (login == null) login = new Login(25, 8);
        if (passwordChange == null) passwordChange = new PasswordChange(5);
        if (http == null) http = new Http(List.of());
        if (ml == null) ml = new Service("", 800);
        if (threat == null) threat = new Service("", 800);
        if (investigation == null) investigation = new Service("", 800);
    }

    public record Jwt(String secret, long ttlMinutes, boolean allowDevSecret) {
        public Jwt {
            if (ttlMinutes <= 0) throw new IllegalArgumentException("ttlMinutes");
        }
    }

    public record RiskEngine(String url, int timeoutMs) {}

    public record Seed(boolean enabled, String credentialsFile, boolean anchorToday, boolean allowDemoReset) {}

    public record PasswordChange(int perMinuteEmail) {
        public PasswordChange {
            if (perMinuteEmail <= 0) throw new IllegalArgumentException("perMinuteEmail");
        }
    }

    public record Check(int perMinute, List<String> trustedProxies) {}

    public record Login(int perMinuteIp, int perMinuteEmail) {}

    /** IPs de proxy (ex.: 127.0.0.1, 10.0.0.0/8) que podem enviar X-Forwarded-For confiável. */
    public record Http(List<String> trustedProxies) {}

    public record Service(String url, int timeoutMs) {}
}
