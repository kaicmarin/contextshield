package com.contextshield.core.service;

import com.contextshield.core.domain.Seller;
import com.contextshield.core.repo.MerchantRepository;
import com.contextshield.core.repo.SellerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * "Confira antes de pagar": analisa a mensagem com score próprio (não é o score do Risk Engine).
 * GREEN só com evidência oficial e poucos sinais. Ausência de evidência vira GRAY, não seguro.
 */
@Service
public class CheckService {

    private static final Logger log = LoggerFactory.getLogger(CheckService.class);
    static final int LIMIAR_ALTO = 40;
    static final int LIMIAR_ATENCAO = 16;
    private static final long CACHE_MS = 60_000;

    private final IntelligenceService intel;
    private final MerchantRepository merchants;
    private final SellerRepository sellers;
    private final AtomicReference<Catalog> catalog = new AtomicReference<>();

    public CheckService(IntelligenceService intel, MerchantRepository merchants, SellerRepository sellers) {
        this.intel = intel;
        this.merchants = merchants;
        this.sellers = sellers;
    }

    public record Finding(String label, String explain) {}

    public record Entity(String kind, String value, int reported, String status) {}

    public record Signal(String family, String label, String explain, int weight) {}

    public record Result(String level, String title, String summary, List<Finding> types, List<Finding> cues,
                         List<Entity> entities, List<String> guidance, int score, List<Signal> signals,
                         List<Finding> unverified, String verdict, String analyzedAt) {}

    private record Catalog(Set<String> official, List<Seller> sellerList, long at) {}

    public Result analyze(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return new Result("GRAY", "Nada para conferir", "Cole uma mensagem, link, telefone ou endereço.",
                    List.of(), List.of(), List.of(), List.of(), 0, List.of(), List.of(), "insuficiente", stamp());
        }

        Catalog cat = catalog();
        List<CheckSignals.Hit> hits = new ArrayList<>(CheckSignals.detect(t));
        List<Finding> unverified = new ArrayList<>();
        List<Entity> entities = new ArrayList<>();
        IntelAcc acc = new IntelAcc();

        String digitsOnly = t.replaceAll("\\D", "");
        boolean officialPhone = digitsOnly.equals(IntelText.OFFICIAL_CENTRAL.replaceAll("\\D", ""));
        boolean officialHost = false;

        for (String d : IntelText.phones(t)) {
            IntelligenceService.IntelHint hint = safeHint(IntelText.phoneEntityId(d), acc);
            int reported = hint == null ? 0 : hint.reports();
            String status = hint == null ? null : hint.status();
            takeIntel(hint, acc, unverified);
            entities.add(new Entity("phone", IntelText.formatPhone(d), reported, status));
        }

        for (CheckUrl.Extracted url : CheckUrl.extract(t, cat.official())) {
            switch (url.kind()) {
                case OFFICIAL -> {
                    officialHost = true;
                    entities.add(new Entity("official-domain", url.host(), 0, null));
                }
                case SIMILAR -> {
                    hits.add(new CheckSignals.Hit("url-similar", "Endereço parecido com uma marca conhecida",
                            "O link usa o nome do banco ou da loja, mas não é o site oficial.", 24, false));
                    url.notes().forEach(n -> unverified.add(new Finding("Endereço enganoso", n)));
                    addDomain(url.host(), entities, acc, unverified);
                }
                case SHORTENER -> {
                    hits.add(new CheckSignals.Hit("url-shortener", "Link encurtado",
                            "O destino real não é visível sem abrir o endereço. Não abrimos o link.", 18, false));
                    unverified.add(new Finding("URL encurtada não expandida",
                            "Não abrimos " + url.host() + " para ver o destino. Encurtador some o endereço real."));
                    addDomain(url.host(), entities, acc, unverified);
                }
                case UNKNOWN -> {
                    unverified.add(new Finding("Domínio desconhecido",
                            url.host() + " não é um endereço oficial conhecido. Isso não prova que seja golpe."));
                    addDomain(url.host(), entities, acc, unverified);
                    if (hits.stream().anyMatch(CheckSignals.Hit::type)) {
                        hits.add(new CheckSignals.Hit("url-unknown", "Endereço fora da lista oficial",
                                "O link não é de um canal que reconhecemos.", 4, false));
                    }
                }
            }
            if (url.notes().stream().anyMatch(n -> n.contains("punycode") || n.contains("alfabetos"))) {
                hits.add(new CheckSignals.Hit("url-obfuscation", "Endereço com letras disfarçadas",
                        "O host mistura alfabetos ou usa punycode, o que pode esconder o nome real.", 6, false));
            }
            if (url.notes().stream().anyMatch(n -> n.contains("IP"))) {
                hits.add(new CheckSignals.Hit("url-ip", "Link com endereço IP",
                        "Sites de banco e de loja não pedem acesso por número de IP.", 16, false));
            }
            if (url.notes().stream().anyMatch(n -> n.contains("@"))) {
                hits.add(new CheckSignals.Hit("url-userinfo", "Link que esconde o destino",
                        "O que aparece antes do @ não é o site que abre.", 20, false));
            }
        }

        try {
            intel.matchAddressText(t).ifPresent(a -> {
                IntelligenceService.IntelHint hint = intel.hintFor(IntelText.destinationEntityId(a.getId()));
                takeIntel(hint, acc, unverified);
                entities.add(new Entity("destination",
                        a.getLine1() + (a.getLine2() != null ? " — " + a.getLine2() : ""),
                        hint.reports(), hint.status()));
            });
        } catch (RuntimeException e) {
            acc.failed = true;
            log.warn("inteligência indisponível ao cruzar endereço");
        }

        String norm = IntelText.strip(t);
        for (Seller s : cat.sellerList()) {
            if (!norm.contains(IntelText.strip(s.getName()))) continue;
            long days = ChronoUnit.DAYS.between(s.getSince(), LocalDate.now());
            IntelligenceService.IntelHint hint = safeHint(IntelText.sellerEntityId(s.getId()), acc);
            int reported = hint == null ? 0 : hint.reports();
            String status = hint == null ? null : hint.status();
            entities.add(new Entity("seller",
                    s.getName() + " · vende há " + days + " dias · " + s.getClaimsRate() + "% de reclamações",
                    reported, status));
            if (days < 60 || s.getClaimsRate().doubleValue() >= 3) {
                hits.add(new CheckSignals.Hit("seller", "Vendedor novo ou com muitas reclamações",
                        "Pouco tempo de loja ou reclamações acima de 3% pedem cuidado redobrado.", 10, false));
            }
            takeIntel(hint, acc, unverified);
        }

        if (acc.weight > 0) {
            hits.add(new CheckSignals.Hit("intel", intelLabel(acc.status), intelExplain(acc.status),
                    acc.weight, false));
        }

        int score = 0;
        Set<String> seenFamily = new LinkedHashSet<>();
        List<Finding> types = new ArrayList<>();
        List<Finding> cues = new ArrayList<>();
        List<Signal> signals = new ArrayList<>();
        for (CheckSignals.Hit h : hits) {
            if (!seenFamily.add(h.family())) continue;
            score += h.weight();
            signals.add(new Signal(h.family(), h.label(), h.explain(), h.weight()));
            Finding f = new Finding(h.label(), h.explain());
            if (h.type()) types.add(f);
            else cues.add(f);
        }
        score = Math.min(100, score);
        boolean official = officialPhone || officialHost;

        String level;
        String verdict;
        if (acc.confirmed || score >= LIMIAR_ALTO) {
            level = "RED";
            verdict = "alto";
        } else if (score >= LIMIAR_ATENCAO || acc.unverified || (acc.failed && official)) {
            level = "YELLOW";
            verdict = "atencao";
        } else if (official && !acc.failed) {
            level = "GREEN";
            verdict = "baixo";
        } else {
            level = "GRAY";
            verdict = "insuficiente";
        }

        if (acc.failed) {
            unverified.add(new Finding("Não foi possível cruzar relatos",
                    "A consulta à inteligência falhou. Isso não significa que a mensagem seja segura."));
            if ("GREEN".equals(level)) {
                level = "YELLOW";
                verdict = "atencao";
            }
        }

        String title;
        String summary;
        List<String> guidance;
        switch (level) {
            case "RED" -> {
                title = acc.confirmed ? "Isto aparece em relatos confirmados" : "Esta mensagem tem cara de golpe";
                summary = acc.confirmed
                        ? "Há indicador confirmado por analista, além dos sinais da própria mensagem."
                        : "Encontramos vários sinais independentes típicos de roteiro de golpe.";
                guidance = List.of("Não siga as instruções da mensagem.",
                        "Fale com o banco ou a loja por um canal que você mesmo procurou: " + IntelText.OFFICIAL_CENTRAL + ".",
                        "Se já pagou, registre em “Fui vítima” no app.");
            }
            case "YELLOW" -> {
                title = "Confira antes de seguir";
                summary = acc.unverified
                        ? "Há sinais ou um relato ainda não confirmado. Nada disso prova o golpe sozinho."
                        : "Há sinais que pedem atenção, mas nada confirmado em relatos.";
                guidance = List.of("Confirme a informação por conta própria antes de pagar.",
                        "Se houver pressa ou pedido de segredo, pare.");
            }
            case "GREEN" -> {
                title = "Este é o canal oficial";
                summary = officialPhone
                        ? "A central oficial do banco é " + IntelText.OFFICIAL_CENTRAL + "."
                        : "O endereço coincide com um domínio oficial conhecido.";
                guidance = List.of("Isso não garante que o resto da mensagem esteja certo: na dúvida, use só este canal.");
            }
            default -> {
                title = "Não há sinais suficientes";
                summary = "A mensagem não traz evidência clara de golpe nem de um canal oficial. Ausência de sinal não é segurança.";
                guidance = List.of("Na dúvida, confira pelo canal oficial: " + IntelText.OFFICIAL_CENTRAL + ".",
                        "Não abra links nem pague a partir desta mensagem.");
            }
        }

        log.info("check size={} level={} score={} verdict={} families={}",
                t.length(), level, score, verdict, seenFamily);
        return new Result(level, title, summary, List.copyOf(types), List.copyOf(cues), List.copyOf(entities),
                guidance, score, List.copyOf(signals), List.copyOf(unverified), verdict, stamp());
    }

    private static String stamp() {
        return java.time.Instant.now().toString();
    }

    private void addDomain(String host, List<Entity> entities, IntelAcc acc, List<Finding> unverified) {
        IntelligenceService.IntelHint hint = safeHint(IntelText.domainEntityId(host), acc);
        int reported = hint == null ? 0 : hint.reports();
        String status = hint == null ? null : hint.status();
        takeIntel(hint, acc, unverified);
        entities.add(new Entity("domain", host, reported, status));
    }

    private IntelligenceService.IntelHint safeHint(String entityId, IntelAcc acc) {
        try {
            return intel.hintFor(entityId);
        } catch (RuntimeException e) {
            acc.failed = true;
            log.warn("inteligência indisponível para entidade");
            return null;
        }
    }

    private static void takeIntel(IntelligenceService.IntelHint hint, IntelAcc acc, List<Finding> unverified) {
        if (hint == null || hint.reports() <= 0) return;
        String status = hint.status() == null ? "UNVERIFIED" : hint.status();
        if ("DISCARDED".equals(status)) return;
        int w = switch (status) {
            case "CONFIRMED" -> 28;
            case "INVESTIGATING" -> 10;
            case "UNVERIFIED" -> 8;
            default -> 0;
        };
        if (w > acc.weight) {
            acc.weight = w;
            acc.status = status;
        }
        if ("CONFIRMED".equals(status)) acc.confirmed = true;
        if ("UNVERIFIED".equals(status)) {
            acc.unverified = true;
            unverified.add(new Finding("Relato ainda não confirmado",
                    "Há menção em relato aberto, ainda sem confirmação de analista."));
        }
    }

    private static String intelLabel(String status) {
        return switch (status == null ? "UNVERIFIED" : status) {
            case "CONFIRMED" -> "Indicador confirmado em relatos";
            case "INVESTIGATING" -> "Indicador em investigação";
            default -> "Relato ainda não confirmado";
        };
    }

    private static String intelExplain(String status) {
        return switch (status == null ? "UNVERIFIED" : status) {
            case "CONFIRMED" -> "Um analista confirmou que este dado aparece em golpe.";
            case "INVESTIGATING" -> "Há relatos em análise. Isso pede cuidado, não é prova fechada.";
            default -> "Alguém já citou este dado, mas o caso ainda não foi confirmado.";
        };
    }

    private Catalog catalog() {
        Catalog cur = catalog.get();
        long now = System.currentTimeMillis();
        if (cur != null && now - cur.at() < CACHE_MS) return cur;
        Set<String> official = new LinkedHashSet<>(IntelText.OFFICIAL_DOMAINS);
        merchants.findAll().forEach(m -> {
            if (m.getDomain() != null && !m.getDomain().isBlank()) official.add(m.getDomain().toLowerCase());
        });
        Catalog next = new Catalog(Set.copyOf(official), List.copyOf(sellers.findAll()), now);
        catalog.set(next);
        return next;
    }

    private static final class IntelAcc {
        int weight;
        String status;
        boolean confirmed;
        boolean unverified;
        boolean failed;
    }
}
