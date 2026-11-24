package com.contextshield.ml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Segunda avaliação. Os campos novos só existem depois que o cliente respondeu.
 * Um zero com {@code unknownAnswers = 1} é informação ausente, não uma negação.
 */
public final class ContextFeatures {
    private ContextFeatures() {}

    public static final String[] EXTRA = {
            "declaredAsked", "declaredContact", "declaredAddressGiven", "declaredCoached",
            "unknownAnswers", "declaredContradiction"
    };

    public static final String[] NAMES = join(Features.NAMES, EXTRA);

    public record Slot(String name, String provenance, String meaning) {}

    public static final List<Slot> SLOTS = List.of(
            new Slot("declaredAsked", "DECLARADA", "O cliente disse que alguém pediu a operação. Não é confirmação."),
            new Slot("declaredContact", "DECLARADA", "O cliente disse que houve telefone ou mensagem. O sistema não viu a ligação."),
            new Slot("declaredAddressGiven", "DECLARADA", "O cliente disse que o endereço foi passado por outra pessoa."),
            new Slot("declaredCoached", "DECLARADA", "O cliente disse que alguém orientou as respostas."),
            new Slot("unknownAnswers", "DESCONHECIDA", "Faltou ao menos uma resposta. Os zeros ao lado não significam negação."),
            new Slot("declaredContradiction", "DECLARADA", "A compra foi declarada como própria, mas a resposta diz que alguém pediu."));

    public static double[] vector(Map<String, Object> checkout) {
        double[] base = Features.vector(checkout);
        Map<?, ?> answers = checkout.get("answers") instanceof Map<?, ?> map ? map : null;
        boolean any = answers != null && !answers.isEmpty();
        String origin = value(answers, "origin");
        String contact = value(answers, "contact");
        String address = value(answers, "address");
        String coached = value(answers, "coached");
        boolean unknown = !any || origin.isEmpty() || contact.isEmpty() || address.isEmpty() || coached.isEmpty();
        String declared = checkout.get("declaredOrigin") == null ? "" : String.valueOf(checkout.get("declaredOrigin"));
        double[] extra = {
                "asked".equals(origin) ? 1 : 0,
                "phone".equals(contact) || "message".equals(contact) ? 1 : 0,
                "given".equals(address) ? 1 : 0,
                "yes".equals(coached) ? 1 : 0,
                unknown ? 1 : 0,
                "self".equals(declared) && "asked".equals(origin) ? 1 : 0
        };
        double[] row = Arrays.copyOf(base, base.length + extra.length);
        System.arraycopy(extra, 0, row, base.length, extra.length);
        return row;
    }

    public static Map<String, String> provenance(Map<String, Object> checkout) {
        double[] row = vector(checkout);
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < EXTRA.length; i++) {
            Slot slot = SLOTS.get(i);
            double value = row[Features.NAMES.length + i];
            String state = slot.name().equals("unknownAnswers")
                    ? (value == 1 ? "DESCONHECIDA" : "RESPONDIDA")
                    : (row[row.length - 2] == 1 && value == 0 ? "DESCONHECIDA" : slot.provenance());
            out.put(slot.name(), state);
        }
        return out;
    }

    private static String value(Map<?, ?> answers, String key) {
        if (answers == null || answers.get(key) == null) return "";
        return String.valueOf(answers.get(key));
    }

    private static String[] join(String[] left, String[] right) {
        List<String> names = new ArrayList<>(left.length + right.length);
        names.addAll(Arrays.asList(left));
        names.addAll(Arrays.asList(right));
        return names.toArray(String[]::new);
    }
}
