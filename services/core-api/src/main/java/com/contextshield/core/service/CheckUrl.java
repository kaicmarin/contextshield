package com.contextshield.core.service;

import java.net.IDN;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrai e classifica URLs e hosts de um texto. Não abre o endereço (sem HTTP, sem SSRF).
 */
public final class CheckUrl {
    private CheckUrl() {}

    public enum Kind { OFFICIAL, SIMILAR, SHORTENER, UNKNOWN }

    public record Extracted(String raw, String host, String registrable, Kind kind, List<String> notes) {}

    private static final Pattern CANDIDATE = Pattern.compile(
            "(?i)(?:https?://)?(?:www\\.)?(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+)(?::\\d{2,5})?(?:/[^\\s<>\"')\\]]*)?");
    private static final Pattern IPV4 = Pattern.compile(
            "(?i)(?:https?://)?(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?::\\d{2,5})?(?:/[^\\s<>\"')\\]]*)?");
    private static final Pattern USERINFO = Pattern.compile(
            "(?i)https?://[^\\s/@]{1,80}@[^\\s<>\"')\\]]+");

    static final Set<String> SHORTENERS = Set.of(
            "bit.ly", "t.co", "tinyurl.com", "cutt.ly", "ow.ly", "is.gd", "rb.gy",
            "rebrand.ly", "shorturl.at", "lnkd.in", "goo.gl", "tiny.cc", "s.id", "b.link");

    private static final Set<String> COMPOUND_TLD = Set.of("com.br", "net.br", "org.br", "gov.br", "edu.br");

    public static List<Extracted> extract(String text, Set<String> official) {
        Set<String> seen = new LinkedHashSet<>();
        List<Extracted> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        collect(USERINFO.matcher(text), official, seen, out);
        collect(IPV4.matcher(text), official, seen, out);
        collect(CANDIDATE.matcher(text), official, seen, out);
        return out;
    }

    private static void collect(Matcher m, Set<String> official, Set<String> seen, List<Extracted> out) {
        while (m.find()) {
            String raw = m.group().replaceAll("[.,;:!?)]+$", "");
            Extracted e = classify(raw, official);
            if (e == null || !seen.add(e.host())) continue;
            out.add(e);
        }
    }

    public static Set<String> hosts(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (Extracted e : extract(text, Set.of())) out.add(e.host());
        return out;
    }

    public static Extracted classify(String raw, Set<String> official) {
        String host = hostOf(raw);
        if (host == null || host.isBlank()) return null;
        List<String> notes = new ArrayList<>();
        if (host.startsWith("xn--") || host.contains(".xn--")) notes.add("Endereço em punycode (pode esconder letras parecidas).");
        if (mixedScript(host)) notes.add("Mistura de alfabetos no endereço.");
        if (raw.contains("@")) notes.add("O texto antes do @ esconde o destino real.");
        if (host.matches("(?:\\d{1,3}\\.){3}\\d{1,3}")) notes.add("Endereço IP no lugar de um nome de site.");
        if (host.split("\\.").length >= 5) notes.add("Muitos subdomínios no endereço.");
        String registrable = registrable(host);
        Kind kind = Kind.UNKNOWN;
        Set<String> off = official == null ? Set.of() : official;
        if (isOfficial(host, off)) {
            kind = Kind.OFFICIAL;
        } else if (SHORTENERS.contains(host) || SHORTENERS.contains(registrable)) {
            kind = Kind.SHORTENER;
            notes.add("Encurtador: o destino real não é visível sem abrir o link.");
        } else if (imitates(host, off, notes)) {
            kind = Kind.SIMILAR;
        }
        return new Extracted(raw, host, registrable, kind, List.copyOf(notes));
    }

    public static String hostOf(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (!s.contains("://")) s = "http://" + s;
        try {
            URI uri = URI.create(s);
            String host = uri.getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            try {
                host = IDN.toASCII(host);
            } catch (IllegalArgumentException ignored) {
                return host;
            }
            return host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String registrable(String host) {
        String[] p = host.split("\\.");
        if (p.length < 2) return host;
        String last2 = p[p.length - 2] + "." + p[p.length - 1];
        if (p.length >= 3 && COMPOUND_TLD.contains(last2))
            return p[p.length - 3] + "." + last2;
        return last2;
    }

    private static boolean isOfficial(String host, Set<String> official) {
        for (String o : official) {
            if (host.equals(o) || host.endsWith("." + o)) return true;
        }
        return false;
    }

    private static boolean imitates(String host, Set<String> official, List<String> notes) {
        Set<String> brands = new LinkedHashSet<>();
        for (String o : official) brands.add(o.split("\\.")[0]);
        brands.add("aureon");
        brands.add("nexa");
        String flat = host.replace(".", "").replace("-", "");
        for (String brand : brands) {
            if (brand.length() < 3) continue;
            if (host.equals(brand) || host.startsWith(brand + ".") || host.contains("." + brand + ".")
                    || host.contains(brand + "-") || host.contains("-" + brand)) {
                notes.add("Usa o nome “" + brand + "”, mas não é o endereço oficial.");
                return true;
            }
            if (flat.contains(brand) && !isOfficial(host, official)) {
                notes.add("O endereço inclui “" + brand + "” fora do domínio oficial.");
                return true;
            }
            String first = host.split("\\.")[0];
            if (close(first, brand) || close(foldConfusable(first), brand)) {
                notes.add("O nome “" + first + "” é parecido com “" + brand + "”.");
                return true;
            }
        }
        return false;
    }

    public static boolean close(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return false;
        int n = a.length(), m = b.length();
        if (Math.abs(n - m) > 2 || Math.min(n, m) < 4) return false;
        return distance(a, b) <= 2;
    }

    static int distance(String a, String b) {
        int n = a.length(), m = b.length();
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[m];
    }

    public static String foldConfusable(String s) {
        return s.replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('5', 's').replace('8', 'b');
    }

    private static boolean mixedScript(String host) {
        boolean latin = false, other = false;
        for (int i = 0; i < host.length(); i++) {
            Character.UnicodeScript sc = Character.UnicodeScript.of(host.charAt(i));
            if (sc == Character.UnicodeScript.COMMON || sc == Character.UnicodeScript.INHERITED) continue;
            if (sc == Character.UnicodeScript.LATIN) latin = true;
            else other = true;
        }
        return latin && other;
    }
}
