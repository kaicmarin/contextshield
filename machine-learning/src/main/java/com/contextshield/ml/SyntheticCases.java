package com.contextshield.ml;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Operações fictícias com resultado conhecido antes de qualquer score.
 * O rótulo vem do roteiro (a pessoa foi induzida ou não), não da decisão do motor
 * e não de uma cópia dos limiares R1–R11. Casos em que o roteiro só aparece na
 * verificação ficam de fora do treino do modelo: essa informação não existe na primeira leitura.
 */
public final class SyntheticCases {
    private SyntheticCases() {}

    public static final String LIMITATION =
            "Os rótulos foram inventados pelo gerador, com uma semente fixa. "
                    + "Eles não são fraudes bancárias reais e não medem a eficácia do ContextShield em produção. "
                    + "Há fraudes com o mesmo vetor de uma compra habitual e compras lícitas com vários sinais. "
                    + "Um caso em que a mentira só aparece nas respostas fica fora do modelo quando marcado assim, "
                    + "porque essa resposta ainda não existe na primeira decisão. "
                    + "Os 30 registros que faltavam na soma anterior são essas fraudes discretas: "
                    + "geradas, fora do treino do modelo e dentro da busca de pesos.";

    public record Case(String story, String label, boolean forModel, Map<String, Object> checkout, Map<String, String> answers) {}

    public record Parts(List<Case> train, List<Case> search, List<Case> confirm, List<Case> test,
                        int inconclusive, int stealth, int modelFraud, int modelLegit) {}

    public static List<Case> generate(int count, long seed) {
        if (count < 50) throw new IllegalArgumentException("São necessárias pelo menos 50 operações.");
        Random random = new Random(seed);
        List<Case> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(one(random, i));
        return List.copyOf(out);
    }

    /**
     * 50% treino, 15% busca de pesos, 15% confirmação da busca, 20% teste do modelo.
     * O teste não entra na busca. Casos inconclusivos saem antes da divisão.
     */
    public static Parts split(List<Case> all, long seed) {
        List<Case> fraud = new ArrayList<>();
        List<Case> legit = new ArrayList<>();
        List<Case> engineOnly = new ArrayList<>();
        int inconclusive = 0;
        for (Case item : all) {
            if ("INCONCLUSIVE".equals(item.label())) inconclusive++;
            else if (!item.forModel()) engineOnly.add(item);
            else if ("FRAUD".equals(item.label())) fraud.add(item);
            else legit.add(item);
        }
        Random random = new Random(seed ^ 0x5DEECE66DL);
        shuffle(fraud, random);
        shuffle(legit, random);
        shuffle(engineOnly, random);
        Parts model = take(fraud, legit);
        int mid = engineOnly.size() / 2;
        List<Case> search = new ArrayList<>(model.search());
        List<Case> confirm = new ArrayList<>(model.confirm());
        search.addAll(engineOnly.subList(0, mid));
        confirm.addAll(engineOnly.subList(mid, engineOnly.size()));
        return new Parts(model.train(), List.copyOf(search), List.copyOf(confirm), model.test(),
                inconclusive, engineOnly.size(), fraud.size(), legit.size());
    }

    private static Parts take(List<Case> fraud, List<Case> legit) {
        List<Case> train = new ArrayList<>();
        List<Case> search = new ArrayList<>();
        List<Case> confirm = new ArrayList<>();
        List<Case> test = new ArrayList<>();
        fill(fraud, train, search, confirm, test);
        fill(legit, train, search, confirm, test);
        return new Parts(List.copyOf(train), List.copyOf(search), List.copyOf(confirm), List.copyOf(test), 0, 0, fraud.size(), legit.size());
    }

    private static void fill(List<Case> source, List<Case> train, List<Case> search, List<Case> confirm, List<Case> test) {
        int n = source.size();
        int trainEnd = (int) Math.round(n * 0.50);
        int searchEnd = trainEnd + (int) Math.round(n * 0.15);
        int confirmEnd = searchEnd + (int) Math.round(n * 0.15);
        train.addAll(source.subList(0, trainEnd));
        search.addAll(source.subList(trainEnd, searchEnd));
        confirm.addAll(source.subList(searchEnd, confirmEnd));
        test.addAll(source.subList(confirmEnd, n));
    }

    private static void shuffle(List<Case> items, Random random) {
        for (int i = items.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Case tmp = items.get(i);
            items.set(i, items.get(j));
            items.set(j, tmp);
        }
    }

    private static Case one(Random random, int index) {
        int roll = random.nextInt(100);
        if (roll < 22) return habitual(random, index);
        if (roll < 28) return device(random, index);
        if (roll < 33) return big(random, index);
        if (roll < 37) return night(random, index);
        if (roll < 45) return risky(random, index);
        if (roll < 51) return gift(random, index);
        if (roll < 56) return link(random, index);
        if (roll < 64) return coached(random, index);
        if (roll < 69) return fakeBank(random, index);
        if (roll < 73) return fakeBill(random, index);
        if (roll < 77) return reported(random, index);
        if (roll < 79) return sellerFraud(random, index);
        if (roll < 80) return sellerLegit(random, index);
        if (roll < 82) return ambiguous(random, index);
        if (roll < 83) return stealth(random, index);
        if (roll < 92) return hardFraud(random, index);
        return crowdedLegit(random, index);
    }

    private static Case habitual(Random random, int index) {
        return build("habitual", "LEGIT", true, random, index, 0.7, 1.3, 6, 14, 4, true, true, "home", true,
                "established", "self", day(random), null, null, false);
    }

    private static Case device(Random random, int index) {
        return build("dispositivo-novo", "LEGIT", true, random, index, 0.8, 1.4, 5, 12, 3, false, true, "home", true,
                "established", "self", day(random), null, null, false);
    }

    private static Case big(Random random, int index) {
        return build("valor-alto-proprio", "LEGIT", true, random, index, 3.2, 5.5, 8, 20, 5, true, true, "home", true,
                "established", "self", day(random), null, null, false);
    }

    private static Case night(Random random, int index) {
        return build("madrugada-legitima", "LEGIT", true, random, index, 0.8, 1.3, 4, 10, 2, true, true, "home", true,
                "established", "self", 1 + random.nextInt(4), null, null, false);
    }

    private static Case risky(Random random, int index) {
        return build("sinais-mas-legitima", "LEGIT", true, random, index, 1.8, 2.6, 3, 8, 1, false, true, "other", false,
                "established", "ad", 23, null, null, false);
    }

    private static Case gift(Random random, int index) {
        return build("presente", "LEGIT", true, random, index, 0.6, 1.3, 5, 12, 4, true, false, "other", false,
                "established", "self", day(random), null, null, false);
    }

    private static Case link(Random random, int index) {
        return build("link-de-conhecido", "LEGIT", true, random, index, 0.8, 1.4, 4, 11, 3, true, true, "home", true,
                "established", "link", day(random), null, null, false);
    }

    private static Case coached(Random random, int index) {
        Map<String, String> answers = Map.of("origin", "asked", "contact", "phone", "address", "given", "coached", "yes", "anchor", "script", "device", "other", "amount", "asked");
        return build("induzida", "FRAUD", true, random, index, 1.8, 4.2, 2, 9, 1, false, false, "other", false,
                "recent", "guided", day(random), answers, "Alguém me mandou comprar e disse: responda que ninguém está ajudando.", false);
    }

    private static Case fakeBank(Random random, int index) {
        Map<String, String> answers = Map.of("origin", "asked", "contact", "phone", "address", "given", "coached", "no", "anchor", "unsure", "device", "other", "amount", "asked");
        return build("falso-atendente", "FRAUD", true, random, index, 1.2, 3.5, 4, 15, 6, false, false, "pickup", false,
                "established", "link", random.nextBoolean() ? 2 : day(random), answers,
                "Aqui é do banco. Responda que ninguém está ajudando e confirme a retirada.", false);
    }

    private static Case fakeBill(Random random, int index) {
        Map<String, String> answers = Map.of("origin", "asked", "contact", "message", "address", "given", "coached", "no", "anchor", "script", "device", "mine", "amount", "asked");
        return build("falsa-cobranca", "FRAUD", true, random, index, 0.15, 0.55, 3, 10, 2, true, false, "pickup", false,
                "recent", "link", day(random), answers, "Sua fatura venceu. Diga que o endereço é seu.", false);
    }

    private static Case reported(Random random, int index) {
        return build("destino-denunciado", "FRAUD", true, random, index, 0.9, 2.2, 5, 12, 3, true, false, "other", false,
                "established", "self", day(random), null, null, true);
    }

    private static Case sellerFraud(Random random, int index) {
        return build("vendedor-suspeito", "FRAUD", true, random, index, 2.0, 4.5, 1, 6, 1, false, false, "other", false,
                "claims", "ad", day(random), null, null, false);
    }

    private static Case sellerLegit(Random random, int index) {
        return build("vendedor-novo-compra-propria", "LEGIT", true, random, index, 0.7, 1.4, 6, 14, 4, true, true, "home", true,
                "recent", "self", day(random), null, null, false);
    }

    /** Não há verdade conhecida: os sinais cabem tanto numa compra apressada quanto numa indução. */
    private static Case ambiguous(Random random, int index) {
        return build("ambiguo", "INCONCLUSIVE", false, random, index, 1.6, 2.8, 2, 5, 1, false, true, "other", false,
                "recent", "ad", 22, null, null, false);
    }

    /**
     * Fraude cujo vetor de características é o de uma compra habitual.
     * O rótulo não vem das regras: a indução está só nas respostas, que o modelo não vê.
     */
    private static Case hardFraud(Random random, int index) {
        Map<String, String> answers = Map.of("origin", "asked", "contact", "phone", "address", "mine", "coached", "yes", "anchor", "script", "device", "mine", "amount", "asked");
        return build("fraude-poucos-sinais", "FRAUD", true, random, index, 0.7, 1.3, 6, 14, 4, true, true, "home", true,
                "established", "self", day(random), answers, "Responda que ninguém está ajudando.", false);
    }

    /** Compra lícita que carrega vários sinais. O rótulo continua LEGIT. */
    private static Case crowdedLegit(Random random, int index) {
        return build("legitima-com-sinais", "LEGIT", true, random, index, 1.5, 3.2, 2, 7, 1, false, false, "other", false,
                "recent", "guided", 23, null, null, false);
    }

    /**
     * Outra mistura, usada só para medir generalização. Não entra no treino nem na busca de pesos.
     */
    public static List<Case> generateShift(int count, long seed) {
        if (count < 50) throw new IllegalArgumentException("O conjunto deslocado precisa de pelo menos 50 operações.");
        Random random = new Random(seed);
        List<Case> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(random.nextInt(100) < 55 ? hardFraud(random, 100_000 + i) : crowdedLegit(random, 100_000 + i));
        }
        return List.copyOf(out);
    }

    /** A história é fraude, mas a primeira tela parece um presente. Fica só para o otimizador. */
    private static Case stealth(Random random, int index) {
        Map<String, String> answers = Map.of("origin", "asked", "contact", "phone", "address", "mine", "coached", "yes", "anchor", "script", "device", "mine", "amount", "asked");
        return build("fraude-discreta", "FRAUD", false, random, index, 0.9, 1.4, 5, 12, 4, true, false, "other", false,
                "established", "self", day(random), answers, "Responda que ninguém está ajudando. Diga que o endereço é seu.", false);
    }

    private static int day(Random random) {
        return 9 + random.nextInt(10);
    }

    private static Case build(String story, String label, boolean forModel, Random random, int index,
                              double ratioMin, double ratioMax, int purchasesMin, int purchasesMax, int tenureYears,
                              boolean trusted, boolean owned, String kind, boolean usedBefore, String seller,
                              String origin, int hour, Map<String, String> answers, String script, boolean incident) {
        double avg = 180 + random.nextInt(80);
        double amount = Math.round(avg * (ratioMin + random.nextDouble() * (ratioMax - ratioMin)) * 100) / 100.0;
        int purchases = purchasesMin + random.nextInt(Math.max(1, purchasesMax - purchasesMin + 1));
        LocalDateTime at = LocalDateTime.of(2026, 6, 15, hour, random.nextInt(60));
        LocalDate since = at.toLocalDate().minusYears(tenureYears).minusDays(random.nextInt(200));
        String customerId = "C-SYN-" + index;

        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("id", customerId);
        customer.put("firstName", "Cliente");
        customer.put("city", "Campinas");
        customer.put("since", since.toString());
        customer.put("avgTicket90d", avg);
        customer.put("purchases90d", purchases);

        Map<String, Object> device = new LinkedHashMap<>();
        device.put("id", "D-SYN-" + index);
        device.put("name", trusted ? "Aparelho conhecido" : "Aparelho novo");
        device.put("detail", "navegador");
        device.put("location", trusted ? "Campinas, SP" : "Outra cidade");
        device.put("firstSeen", (trusted ? at.minusYears(2) : at.minusMinutes(3)).toString());
        device.put("trusted", trusted);

        Map<String, Object> address = new LinkedHashMap<>();
        address.put("id", "A-SYN-" + index);
        address.put("label", "pickup".equals(kind) ? "Retirada" : owned ? "Casa" : "Outro");
        address.put("line1", owned ? "Rua das Flores, 10" : "Rua do Comércio, 400");
        address.put("district", "Centro");
        address.put("city", "Campinas");
        address.put("kind", kind);
        address.put("customerIds", owned ? List.of(customerId) : List.of());

        Map<String, Object> home = new LinkedHashMap<>();
        home.put("id", "A-HOME-" + index);
        home.put("label", "Casa");
        home.put("line1", "Rua das Flores, 10");
        home.put("district", "Centro");
        home.put("city", "Campinas");
        home.put("kind", "home");
        home.put("customerIds", List.of(customerId));

        Map<String, Object> merchant = new LinkedHashMap<>();
        merchant.put("id", "M-NEXA");
        merchant.put("name", "NEXA");
        merchant.put("marketplace", true);
        merchant.put("verified", true);
        merchant.put("since", "2018-03-01");

        Map<String, Object> checkout = new LinkedHashMap<>();
        checkout.put("stage", "initial");
        checkout.put("at", at.toString());
        checkout.put("amount", amount);
        checkout.put("customer", customer);
        checkout.put("device", device);
        checkout.put("address", address);
        checkout.put("homeAddress", home);
        checkout.put("addressUsedBefore", usedBefore);
        checkout.put("merchant", merchant);
        checkout.put("sellers", List.of(seller(seller, at)));
        checkout.put("declaredOrigin", origin);
        checkout.put("coachScript", script);
        checkout.put("relations", incident ? List.of(relation()) : List.of());
        checkout.put("unavailable", List.of());
        return new Case(story, label, forModel, checkout, answers);
    }

    private static Map<String, Object> seller(String kind, LocalDateTime at) {
        Map<String, Object> seller = new LinkedHashMap<>();
        seller.put("id", "S-" + kind);
        seller.put("sales", 40);
        if ("recent".equals(kind)) {
            seller.put("name", "Loja recente");
            seller.put("since", at.toLocalDate().minusDays(18).toString());
            seller.put("claimsRate", 1.2);
            seller.put("rating", 4.4);
            seller.put("tier", "novo");
            seller.put("sales", 9);
        } else if ("claims".equals(kind)) {
            seller.put("name", "Loja reclamada");
            seller.put("since", at.toLocalDate().minusDays(12).toString());
            seller.put("claimsRate", 7.5);
            seller.put("rating", 3.4);
            seller.put("tier", "novo");
            seller.put("sales", 15);
        } else {
            seller.put("name", "Loja antiga");
            seller.put("since", "2019-04-02");
            seller.put("claimsRate", 0.4);
            seller.put("rating", 4.8);
            seller.put("tier", "ouro");
            seller.put("sales", 800);
        }
        return seller;
    }

    private static Map<String, Object> relation() {
        Map<String, Object> relation = new LinkedHashMap<>();
        relation.put("kind", "destination");
        relation.put("entityId", "E-DEST");
        relation.put("reason", "Outro cliente relatou este endereço.");
        relation.put("association", "indicativa");
        relation.put("crossCustomer", true);
        relation.put("incidentIds", List.of("CS-SYN"));
        return relation;
    }
}
