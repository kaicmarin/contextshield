package com.contextshield.core.service;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extração de telefones, domínios e perfis citados em textos livres (relatos e mensagens). */
public final class IntelText {
    private IntelText() {}

    public static final Pattern PHONE = Pattern.compile("\\(?\\b\\d{2}\\)?\\s?9?\\d{4,5}-?\\d{4}\\b");
    public static final Pattern DOMAIN = Pattern.compile(
            "\\b[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:example|com\\.br|com|net|org|online|site|info)\\b", Pattern.CASE_INSENSITIVE);
    public static final Set<String> OFFICIAL_DOMAINS = Set.of("nexa.example", "aureon.example");
    public static final String OFFICIAL_CENTRAL = "0800 000 0000";

    public record AccountCue(Pattern re, String id, String value) {}

    public static final AccountCue[] ACCOUNT_CUES = {
            new AccountCue(Pattern.compile("central de seguran[çc]a", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), "ENT-ACCOUNT-CENTRAL", "Perfil “Central de Segurança”"),
            new AccountCue(Pattern.compile("setor de pr[êe]mios", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), "ENT-ACCOUNT-PREMIOS", "Perfil “Setor de prêmios”")
    };

    public static String normalizePhone(String raw) {
        String d = raw.replaceAll("\\D", "");
        return d.startsWith("55") && d.length() > 11 ? d.substring(2) : d;
    }

    public static String formatPhone(String d) {
        if (d.length() == 11) return "(" + d.substring(0, 2) + ") " + d.substring(2, 7) + "-" + d.substring(7);
        if (d.length() == 10) return "(" + d.substring(0, 2) + ") " + d.substring(2, 6) + "-" + d.substring(6);
        return d;
    }

    public static String normalizeDomain(String raw) {
        return raw.trim().toLowerCase().replaceFirst("^https?://", "").replaceFirst("^www\\.", "").replaceFirst("[/?#].*$", "");
    }

    public static String phoneEntityId(String digits) {
        return "ENT-PHONE-" + digits.substring(Math.max(0, digits.length() - 8));
    }

    public static String domainEntityId(String host) {
        String core = host.replaceFirst("\\.example$", "").replaceAll("[^a-z0-9]", "").toUpperCase();
        return "ENT-DOMAIN-" + core.substring(0, Math.min(20, core.length()));
    }

    public static String destinationEntityId(String addressId) {
        return "ENT-DEST-" + addressId.replaceFirst("^ADDR-", "");
    }

    public static String sellerEntityId(String sellerId) {
        return "ENT-" + sellerId;
    }

    public static Set<String> phones(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = PHONE.matcher(text == null ? "" : text);
        while (m.find()) {
            String d = normalizePhone(m.group());
            if (d.length() >= 10) out.add(d);
        }
        return out;
    }

    public static Set<String> domains(String text) {
        Set<String> out = new LinkedHashSet<>(CheckUrl.hosts(text));
        Matcher m = DOMAIN.matcher(text == null ? "" : text);
        while (m.find()) out.add(normalizeDomain(m.group()));
        return out;
    }

    public static String strip(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase()
                .replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Texto para casar regras: acento fora, R$ vira "reais", espaços colapsados. */
    public static String fold(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase();
        t = t.replace("r$", " reais ").replaceAll("[^a-z0-9]+", " ").replaceAll("\\s+", " ").trim();
        return t;
    }

    public static boolean has(String folded, String... words) {
        String hay = " " + folded + " ";
        for (String w : words) {
            if (hay.contains(" " + w + " ")) return true;
        }
        return false;
    }
}
