package com.contextshield.ml;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Conjunto da segunda avaliação. As duas classes respondem o questionário.
 * Parte das fraudes nega a ajuda: esses casos continuam parecidos com compras lícitas.
 */
public final class ContextCases {
    private ContextCases() {}

    public record Case(String story, String label, Map<String, Object> checkout, Map<String, String> answers) {}

    public static List<Case> development(int count, long seed) {
        return generate(count, seed, false);
    }

    /** Outra mistura. Não entra no treino nem na escolha do modelo. */
    public static List<Case> reserved(int count, long seed) {
        return generate(count, seed, true);
    }

    private static List<Case> generate(int count, long seed, boolean reserved) {
        if (count < 80) throw new IllegalArgumentException("O conjunto contextual precisa de pelo menos 80 operações.");
        Random random = new Random(seed);
        List<Case> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int roll = random.nextInt(100);
            out.add(one(random, i, roll, reserved));
        }
        return List.copyOf(out);
    }

    private static Case one(Random random, int index, int roll, boolean reserved) {
        if (!reserved) {
            if (roll < 20) return clean(random, index, false);
            if (roll < 35) return clean(random, index, true);
            if (roll < 50) return coached(random, index);
            if (roll < 65) return partial(random, index);
            if (roll < 77) return unknown(random, index);
            if (roll < 89) return denial(random, index);
            return family(random, index);
        }
        if (roll < 15) return clean(random, index, false);
        if (roll < 30) return clean(random, index, true);
        if (roll < 41) return coached(random, index);
        if (roll < 56) return denial(random, index);
        if (roll < 71) return unknown(random, index);
        if (roll < 86) return family(random, index);
        return partial(random, index);
    }

    private static Case clean(Random random, int index, boolean crowded) {
        Map<String, String> answers = Map.of("origin", "self", "contact", "no", "address", "mine", "coached", "no");
        if (!crowded) return build("legitima-habitual", "LEGIT", random, index, true, "self", 15, answers);
        return build("legitima-com-sinais", "LEGIT", random, index, false, "guided", 23, answers);
    }

    private static Case coached(Random random, int index) {
        Map<String, String> answers = Map.of("origin", "asked", "contact", "phone", "address", "mine", "coached", "yes");
        return build("fraude-orientada", "FRAUD", random, index, true, "self", 14, answers);
    }

    private static Case partial(Random random, int index) {
        Map<String, String> answers = Map.of("coached", "yes");
        return build("fraude-resposta-incompleta", "FRAUD", random, index, true, "self", 16, answers);
    }

    private static Case unknown(Random random, int index) {
        return build("legitima-sem-resposta", "LEGIT", random, index, true, "self", 11, null);
    }

    private static Case denial(Random random, int index) {
        Map<String, String> answers = Map.of("origin", "self", "contact", "no", "address", "mine", "coached", "no");
        return build("fraude-que-nega", "FRAUD", random, index, true, "self", 13, answers);
    }

    private static Case family(Random random, int index) {
        Map<String, String> answers = Map.of("origin", "self", "contact", "phone", "address", "mine", "coached", "no");
        return build("legitima-contato-familiar", "LEGIT", random, index, true, "self", 18, answers);
    }

    private static Case build(String story, String label, Random random, int index, boolean habitual,
                              String origin, int hour, Map<String, String> answers) {
        double avg = 180 + random.nextInt(80);
        double ratio = habitual ? 0.7 + random.nextDouble() * 0.6 : 1.6 + random.nextDouble() * 1.4;
        double amount = Math.round(avg * ratio * 100) / 100.0;
        int purchases = habitual ? 6 + random.nextInt(8) : 2 + random.nextInt(3);
        LocalDateTime at = LocalDateTime.of(2026, 8, 10, hour, random.nextInt(60));
        LocalDate since = at.toLocalDate().minusYears(habitual ? 4 : 1).minusDays(random.nextInt(80));
        String customerId = "C-CTX-" + index;
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("id", customerId);
        customer.put("firstName", "Cliente");
        customer.put("city", "Campinas");
        customer.put("since", since.toString());
        customer.put("avgTicket90d", avg);
        customer.put("purchases90d", purchases);
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("id", "D-CTX-" + index);
        device.put("name", habitual ? "Aparelho conhecido" : "Aparelho novo");
        device.put("detail", "navegador");
        device.put("location", habitual ? "Campinas, SP" : "Outra cidade");
        device.put("firstSeen", (habitual ? at.minusYears(2) : at.minusMinutes(4)).toString());
        device.put("trusted", habitual);
        boolean owned = habitual;
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("id", "A-CTX-" + index);
        address.put("label", owned ? "Casa" : "Outro");
        address.put("line1", owned ? "Rua das Flores, 10" : "Rua do Comércio, 400");
        address.put("district", "Centro");
        address.put("city", "Campinas");
        address.put("kind", owned ? "home" : "other");
        address.put("customerIds", owned ? List.of(customerId) : List.of());
        Map<String, Object> home = new LinkedHashMap<>(address);
        home.put("id", "A-HOME-CTX-" + index);
        home.put("line1", "Rua das Flores, 10");
        home.put("kind", "home");
        home.put("customerIds", List.of(customerId));
        Map<String, Object> merchant = new LinkedHashMap<>();
        merchant.put("id", "M-NEXA");
        merchant.put("name", "NEXA");
        merchant.put("marketplace", true);
        merchant.put("verified", true);
        merchant.put("since", "2018-03-01");
        Map<String, Object> seller = new LinkedHashMap<>();
        seller.put("id", habitual ? "S-old" : "S-new");
        seller.put("name", habitual ? "Loja estabelecida" : "Loja recente");
        seller.put("since", habitual ? "2019-01-01" : at.toLocalDate().minusDays(12).toString());
        seller.put("claimsRate", habitual ? 0.4 : 1.1);
        seller.put("rating", habitual ? 4.8 : 4.2);
        seller.put("tier", habitual ? "ouro" : "novo");
        seller.put("sales", habitual ? 80 : 6);
        Map<String, Object> checkout = new LinkedHashMap<>();
        checkout.put("stage", "initial");
        checkout.put("at", at.toString());
        checkout.put("amount", amount);
        checkout.put("customer", customer);
        checkout.put("device", device);
        checkout.put("address", address);
        checkout.put("homeAddress", home);
        checkout.put("addressUsedBefore", habitual);
        checkout.put("merchant", merchant);
        checkout.put("sellers", List.of(seller));
        checkout.put("declaredOrigin", origin);
        checkout.put("coachScript", null);
        checkout.put("relations", List.of());
        checkout.put("unavailable", List.of());
        if (answers != null) checkout.put("answers", answers);
        return new Case(story, label, checkout, answers);
    }
}
