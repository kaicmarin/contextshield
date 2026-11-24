package com.contextshield.core.service;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Janela fixa de 60 s por chave. Sem texto da mensagem. */
public final class CheckRateLimiter {
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public boolean tryAcquire(String key, int limit, long nowMs) {
        if (key == null || key.isBlank()) key = "unknown";
        Window w = windows.compute(key, (k, old) -> {
            if (old == null || nowMs - old.start >= 60_000L) return new Window(nowMs, 1);
            old.count++;
            return old;
        });
        if (windows.size() > 10_000) evict(nowMs);
        return w.count <= limit;
    }

    private void evict(long nowMs) {
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            if (nowMs - it.next().getValue().start >= 60_000L) it.remove();
        }
    }

    private static final class Window {
        final long start;
        int count;

        Window(long start, int count) {
            this.start = start;
            this.count = count;
        }
    }
}
