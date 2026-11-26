package com.contextshield.core.config;

import com.contextshield.core.service.CheckRateLimiter;
import org.springframework.stereotype.Component;

/** Limite de tentativas de alteração de senha por conta autenticada. */
@Component
public class PasswordChangeLimiter {
    private final CheckRateLimiter byEmail = new CheckRateLimiter();
    private final AppProperties props;

    public PasswordChangeLimiter(AppProperties props) {
        this.props = props;
    }

    public boolean allow(String email) {
        if (email == null || email.isBlank()) return true;
        int limit = props.passwordChange() == null ? 5 : props.passwordChange().perMinuteEmail();
        return byEmail.tryAcquire("pwd:" + email.trim().toLowerCase(), limit, System.currentTimeMillis());
    }
}