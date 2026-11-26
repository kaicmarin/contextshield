package com.contextshield.core.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ActivityCursorTest {

    @Test
    void encodeDecodeRoundTrip() {
        Map<String, Object> event = new LinkedHashMap<>();
        LocalDateTime at = LocalDateTime.of(2026, 6, 1, 10, 15, 30);
        event.put("at", at);
        event.put("key", "shadow:42");
        String raw = ActivityCursor.encode(event);
        ActivityCursor parsed = ActivityCursor.parse(raw).orElseThrow();
        assertEquals(at.withNano(0), parsed.at);
        assertEquals("shadow:42", parsed.key);
        assertEquals(42L, parsed.shadowId());
    }

    @Test
    void equalTimestampsOrderByKey() {
        ActivityCursor cursor = new ActivityCursor(LocalDateTime.of(2026, 1, 1, 0, 0), "shadow:10");
        Map<String, Object> older = Map.of("at", LocalDateTime.of(2026, 1, 1, 0, 0), "key", "shadow:09");
        Map<String, Object> newer = Map.of("at", LocalDateTime.of(2026, 1, 1, 0, 0), "key", "shadow:11");
        assertTrue(cursor.isOlderThan(older));
        assertFalse(cursor.isOlderThan(newer));
    }
}