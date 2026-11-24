package com.contextshield.risk.engine;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/** Lê a mensagem que o cliente recebeu e encontra a frase que manda esconder a orientação. */
final class Coaching {
    private Coaching() {}

    private static final Pattern COACH_LINE = Pattern.compile(
            "responda que|diga que|ningu[ée]m est[áa] ajudando|n[ãa]o (conte|fale|comente)|sigilo",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    static Optional<String> instruction(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        return Arrays.stream(text.split("\\n+|(?<=[.!?])\\s+"))
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .filter(p -> COACH_LINE.matcher(p).find())
                .findFirst()
                .map(hit -> hit.length() > 220 ? hit.substring(0, 217) + "…" : hit);
    }

    static String street(String line1) {
        if (line1 == null) return "";
        return line1.split(",")[0].trim().toLowerCase();
    }

    static String strip(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase()
                .replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Mesmo hash FNV-1a do protótipo, para a ordem das opções ser estável. */
    static boolean stableFlip(String seed) {
        int hash = 0x811c9dc5;
        for (char ch : seed.toCharArray()) {
            hash = (hash ^ ch) * 16777619;
        }
        return (Integer.toUnsignedLong(hash) % 2) == 1;
    }
}
