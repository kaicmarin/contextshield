package com.contextshield.core.config;

/** Quem está chamando a API, extraído do JWT. */
public record CurrentUser(String email, String name, String role, String customerId) {
    public boolean isStaff() {
        return "ANALYST".equals(role) || "ADMIN".equals(role);
    }
}
