package com.contextshield.core.security;

import com.contextshield.core.web.ApiException;
import org.springframework.http.HttpStatus;

/** Regras mínimas de senha (sem expor detalhes que ajudem atacantes). */
public final class PasswordPolicy {
    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 72;

    private PasswordPolicy() {}

    public static void requireNewPassword(String plain) {
        if (plain == null || plain.length() < MIN_LENGTH || plain.length() > MAX_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A nova senha precisa ter de " + MIN_LENGTH + " a " + MAX_LENGTH + " caracteres");
        }
    }
}