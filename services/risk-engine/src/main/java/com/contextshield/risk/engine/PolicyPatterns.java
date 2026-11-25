package com.contextshield.risk.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Padrões de peso para outros comércios. São rascunhos: a compra da loja
 * continua na política publicada de conta e cartão até alguém publicar outra.
 */
public final class PolicyPatterns {
    private PolicyPatterns() {}

    public record Pattern(String profile, String name, String summary, Map<String, Integer> weights) {}

    public static final List<Pattern> ALL = List.of(
            pattern("marketplace", "Marketplace",
                    "Vários vendedores no mesmo site. Vendedor novo, reclamação e entrega fora do cadastro pesam mais. O valor pesa menos porque cada anúncio tem um ticket diferente.",
                    Map.ofEntries(
                            Map.entry("RECENT_SELLER", 12),
                            Map.entry("SELLER_HIGH_CLAIMS", 10),
                            Map.entry("ESTABLISHED_SELLER", -4),
                            Map.entry("SELLER_IN_INCIDENT", 18),
                            Map.entry("NEW_DESTINATION_THIRD", 14),
                            Map.entry("PICKUP_POINT", 6),
                            Map.entry("DESTINATION_IN_INCIDENT", 34),
                            Map.entry("AMOUNT_ABOVE_LOW", 3),
                            Map.entry("AMOUNT_ABOVE_MID", 6),
                            Map.entry("AMOUNT_ABOVE_HIGH", 9),
                            Map.entry("ORIGIN_LINK", 8),
                            Map.entry("ORIGIN_GUIDED", 12))),
            pattern("ecommerce", "Loja própria",
                    "A loja vende o próprio estoque, então o vendedor pesa pouco. Pesam o valor longe do hábito, o horário, o aparelho novo e o endereço que não é da casa.",
                    Map.ofEntries(
                            Map.entry("RECENT_SELLER", 3),
                            Map.entry("SELLER_HIGH_CLAIMS", 2),
                            Map.entry("ESTABLISHED_SELLER", -1),
                            Map.entry("SELLER_IN_INCIDENT", 5),
                            Map.entry("AMOUNT_ABOVE_LOW", 8),
                            Map.entry("AMOUNT_ABOVE_MID", 16),
                            Map.entry("AMOUNT_ABOVE_HIGH", 24),
                            Map.entry("UNUSUAL_HOUR", 8),
                            Map.entry("NEW_DEVICE", 12),
                            Map.entry("NEW_DESTINATION_THIRD", 14),
                            Map.entry("HABITUAL_DESTINATION", -5),
                            Map.entry("KNOWN_DEVICE", -6))),
            pattern("giftcard", "Vale-presente",
                    "O golpe pede que a pessoa compre um vale e entregue o código. Pesam a origem orientada, alguém pedindo a compra e o roteiro repetido. O vendedor da loja pesa pouco.",
                    Map.ofEntries(
                            Map.entry("ORIGIN_GUIDED", 14),
                            Map.entry("ORIGIN_LINK", 10),
                            Map.entry("VER_ASKED", 20),
                            Map.entry("VER_CONTACT", 12),
                            Map.entry("SCRIPT_ECHO", 20),
                            Map.entry("VER_COACHED", 8),
                            Map.entry("AMOUNT_ABOVE_MID", 16),
                            Map.entry("AMOUNT_ABOVE_HIGH", 22),
                            Map.entry("PICKUP_POINT", 6),
                            Map.entry("NEW_DESTINATION_THIRD", 12),
                            Map.entry("RECENT_SELLER", 3))));

    private static Pattern pattern(String profile, String name, String summary, Map<String, Integer> weights) {
        Map<String, Integer> ordered = new LinkedHashMap<>();
        weights.forEach((key, value) -> {
            Weights.check(key, value);
            if (value == Weights.bound(key).fallback()) {
                throw new IllegalArgumentException(name + " repete o peso padrão de " + key);
            }
            ordered.put(key, value);
        });
        return new Pattern(profile, name, summary, Map.copyOf(ordered));
    }
}
