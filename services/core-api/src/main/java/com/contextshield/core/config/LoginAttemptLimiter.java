package com.contextshield.core.config;

import com.contextshield.core.service.CheckRateLimiter;
import org.springframework.stereotype.Component;

/** Limite por e-mail no login (não desativa a conta). */
@Component
public class LoginAttemptLimiter {
    private final CheckRateLimiter byEmail = new CheckRateLimiter();
    private final AppProperties props;

    public LoginAttemptLimiter(AppProperties props) {
        this.props = props;
    }

    public boolean allow(String email) {
        if (email == null || email.isBlank()) return true;
        return byEmail.tryAcquire("mail:" + email.trim().toLowerCase(), props.login().perMinuteEmail(), System.currentTimeMillis());
    }
}