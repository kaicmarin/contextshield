package com.contextshield.core.service;

import org.springframework.jdbc.support.KeyHolder;

/** Lê o id quando o PostgreSQL devolve a linha inteira como chave gerada. */
public final class GeneratedIds {
    private GeneratedIds() {}

    public static long of(KeyHolder keys) {
        var map = keys.getKeys();
        if (map != null) {
            for (var entry : map.entrySet()) {
                if ("id".equalsIgnoreCase(entry.getKey()) && entry.getValue() instanceof Number number) return number.longValue();
            }
        }
        throw new IllegalStateException("Banco não devolveu o id.");
    }
}
