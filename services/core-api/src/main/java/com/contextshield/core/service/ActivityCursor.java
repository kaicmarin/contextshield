package com.contextshield.core.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

/** Cursor estável para paginação do feed (at desc, depois key desc). */
final class ActivityCursor {
    final LocalDateTime at;
    final String key;

    ActivityCursor(LocalDateTime at, String key) {
        this.at = at;
        this.key = key == null ? "" : key;
    }

    static Optional<ActivityCursor> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        int pipe = raw.indexOf('|');
        if (pipe < 1) return Optional.of(new ActivityCursor(null, raw.trim()));
        String atPart = raw.substring(0, pipe).trim();
        String keyPart = raw.substring(pipe + 1).trim();
        try {
            return Optional.of(new ActivityCursor(LocalDateTime.parse(atPart, ISO), keyPart));
        } catch (Exception e) {
            return Optional.of(new ActivityCursor(null, keyPart.isBlank() ? raw.trim() : keyPart));
        }
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    static String encode(Map<String, Object> event) {
        LocalDateTime at = MlActivityService.eventAt(event);
        String key = String.valueOf(event.get("key"));
        if (at == null || LocalDateTime.MIN.equals(at)) return key;
        return at.withNano(0).format(ISO) + "|" + key;
    }

    Long shadowId() {
        if (key == null || !key.startsWith("shadow:")) return null;
        try {
            return Long.parseLong(key.substring("shadow:".length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Verdadeiro se o evento é estritamente mais antigo que o cursor na ordenação (at desc, key desc). */
    boolean isOlderThan(Map<String, Object> event) {
        LocalDateTime eat = MlActivityService.eventAt(event);
        String ekey = String.valueOf(event.get("key"));
        if (at == null) return false;
        if (eat == null) return true;
        int cmp = eat.compareTo(at);
        if (cmp < 0) return true;
        if (cmp > 0) return false;
        return ekey.compareTo(key) < 0;
    }
}