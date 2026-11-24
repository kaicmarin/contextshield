package com.contextshield.risk.engine;

import com.contextshield.risk.model.Assessment.Certificate;
import com.contextshield.risk.model.Assessment.Independence;
import com.contextshield.risk.model.Assessment.Perturbation;
import com.contextshield.risk.model.Assessment.SourceGroup;
import com.contextshield.risk.model.AssessmentInput.EngineConfig;
import com.contextshield.risk.model.Signal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Certificado de fragilidade: refaz a decisão tirando, enfraquecendo ou adulterando cada fonte.
 * Se a decisão muda de "liberar" para "proteger" (ou o contrário), ela é frágil.
 */
public final class Robustness {
    private Robustness() {}

    /** Dados mínimos da avaliação base que o certificado precisa. */
    public record Base(String stage, List<Signal> signals, String decision, String level, int score,
                       int confidence, List<String> degraded) {}

    private static String cls(String d) {
        return RiskModel.isProtective(d) ? "proteger" : "liberar";
    }

    private record Eval(int score, String level, int confidence, String decision, String ruleId) {}

    private static Eval evaluate(List<Signal> signals, Base a, EngineConfig cfg, List<String> degraded) {
        // Ao reavaliar, os pontos voltam ao valor base: o desconto de dependência é recalculado.
        List<Signal> fresh = signals.stream().map(s -> s.withPoints(s.basePoints(), false, null)).toList();
        Policy.Scored sc = Policy.score(fresh, cfg.flag("dependency-discount"), degraded, "recalculated".equals(a.stage()), cfg.weights());
        Policy.Outcome o = Policy.decide(sc, a.stage(), cfg);
        return new Eval(sc.score(), sc.level(), sc.confidence(), o.decision(), o.ruleId());
    }

    private static List<Signal> withDevice(List<Signal> signals, boolean favorable, java.util.Map<String, Integer> w) {
        List<Signal> extra = favorable
                ? List.of(SignalCollector.signal("KNOWN_DEVICE", Weights.value(w, "KNOWN_DEVICE"), "Dispositivo aparenta ser conhecido (identificação falsificada)"),
                          SignalCollector.signal("SESSION_CONSISTENT", Weights.value(w, "SESSION_CONSISTENT"), "Sessão aparenta ser habitual"),
                          SignalCollector.signal("LOCATION_CONSISTENT", Weights.value(w, "LOCATION_CONSISTENT"), "Localização aparenta ser compatível"))
                : List.of(SignalCollector.signal("NEW_DEVICE", Weights.value(w, "NEW_DEVICE"), "Dispositivo era, na verdade, desconhecido"),
                          SignalCollector.signal("SESSION_ANOMALY", Weights.value(w, "SESSION_ANOMALY"), "Sessão atípica que não foi percebida"));
        return Stream.concat(signals.stream().filter(s -> !"device".equals(s.source())), extra.stream()).toList();
    }

    private static List<Signal> withAnswers(List<Signal> signals, boolean lie, java.util.Map<String, Integer> w) {
        List<Signal> out = new ArrayList<>(signals.stream()
                .filter(s -> !s.id().startsWith("VER_") && !s.id().equals("EVIDENCE_DIVERGENCE")).toList());
        boolean third = signals.stream().anyMatch(s -> s.id().equals("NEW_DESTINATION_THIRD"));
        boolean pickup = signals.stream().anyMatch(s -> s.id().equals("PICKUP_POINT"));
        if (lie) {
            out.add(SignalCollector.signal("VER_CLEAR", Weights.value(w, "VER_CLEAR"), "Respostas orientadas: “fui eu”, “ninguém me contatou”, “o endereço é meu”"));
            if (third && pickup) out.add(SignalCollector.signal("EVIDENCE_DIVERGENCE", Weights.value(w, "EVIDENCE_DIVERGENCE"), "Resposta diverge do endereço de retirada"));
            return out;
        }
        out.add(SignalCollector.signal("VER_ASKED", Weights.value(w, "VER_ASKED"), "Respostas verdadeiras: alguém pediu a compra"));
        out.add(SignalCollector.signal("VER_CONTACT", Weights.value(w, "VER_CONTACT"), "Houve contato prévio"));
        if (third) out.add(SignalCollector.signal("VER_ADDRESS_GIVEN", Weights.value(w, "VER_ADDRESS_GIVEN"), "Endereço passado por terceiro"));
        return out;
    }

    public static Certificate certificate(Base a, EngineConfig cfg) {
        List<String> present = a.signals().stream().map(Signal::source).distinct()
                .filter(s -> !s.equals("checkout")).toList();
        List<Perturbation> out = new ArrayList<>();
        boolean recalculated = "recalculated".equals(a.stage());

        record P(String id, String source, String kind, String label, List<Signal> signals, List<String> degraded) {}
        List<P> plan = new ArrayList<>();
        for (String src : present) {
            if (src.equals("customer") && recalculated) continue;
            RiskModel.Source spec = RiskModel.source(src);
            List<String> deg = a.degraded();
            if (spec.hasProvider()) {
                LinkedHashSet<String> d = new LinkedHashSet<>(a.degraded());
                d.add(src);
                deg = List.copyOf(d);
            }
            plan.add(new P(src + "-missing", src, "ausente", spec.label() + " indisponível",
                    a.signals().stream().filter(s -> !s.source().equals(src)).toList(), deg));
            plan.add(new P(src + "-weak", src, "degradada", spec.label() + " com qualidade reduzida (metade do peso)",
                    a.signals().stream().map(s -> s.source().equals(src) ? s.withBasePoints((int) Math.round(s.basePoints() / 2.0)) : s).toList(),
                    a.degraded()));
        }
        boolean protective = RiskModel.isProtective(a.decision());
        if (present.contains("device")) {
            boolean deviceFavorable = a.signals().stream().anyMatch(s -> s.id().equals("KNOWN_DEVICE"));
            if (protective && !deviceFavorable)
                plan.add(new P("device-spoofed", "device", "adulterada", "Dispositivo falsificado para parecer conhecido", withDevice(a.signals(), true, cfg.weights()), a.degraded()));
            if (!protective && deviceFavorable)
                plan.add(new P("device-wrong", "device", "adulterada", "Identificação do dispositivo errada (era desconhecido)", withDevice(a.signals(), false, cfg.weights()), a.degraded()));
        }
        if (recalculated) {
            boolean answeredRisk = a.signals().stream().anyMatch(s -> s.id().equals("VER_ASKED") || s.id().equals("VER_CONTACT") || s.id().equals("VER_ADDRESS_GIVEN"));
            if (protective && answeredRisk)
                plan.add(new P("customer-lie", "customer", "adulterada", "Cliente orientado a mentir na verificação", withAnswers(a.signals(), true, cfg.weights()), a.degraded()));
            if (!protective && a.signals().stream().anyMatch(s -> s.id().equals("VER_CLEAR")))
                plan.add(new P("customer-truth", "customer", "adulterada", "Respostas eram falsas (houve orientação)", withAnswers(a.signals(), false, cfg.weights()), a.degraded()));
        }
        for (P p : plan) {
            Eval r = evaluate(p.signals(), a, cfg, p.degraded());
            out.add(new Perturbation(p.id(), p.source(), p.kind(), p.label(), r.score(), r.level(), r.decision(), r.ruleId(),
                    !r.decision().equals(a.decision()), !cls(r.decision()).equals(cls(a.decision()))));
        }

        List<Perturbation> fragile = out.stream().filter(p -> p.crossesClass() || ("adulterada".equals(p.kind()) && p.changed())).toList();
        List<Perturbation> degradedP = out.stream().filter(p -> p.changed() || "INCONCLUSIVE".equals(p.level())).toList();
        String status = "ROBUST";
        if ("INCONCLUSIVE".equals(a.level())) status = "INCONCLUSIVE";
        else if (!fragile.isEmpty()) status = "FRAGILE";
        else if (!degradedP.isEmpty() || !a.degraded().isEmpty()) status = "DEGRADED";

        List<String> sensitiveTo = (status.equals("FRAGILE") ? fragile : degradedP).stream()
                .map(Perturbation::source).distinct().toList();
        String names = sensitiveTo.stream().map(s -> RiskModel.source(s).label().toLowerCase()).collect(Collectors.joining(", "));
        String dec = RiskModel.decision(a.decision()).label().toLowerCase();
        String summary = switch (status) {
            case "ROBUST" -> "A decisão de " + dec + " se mantém em todas as " + out.size() + " perturbações testadas.";
            case "FRAGILE" -> "A decisão de " + dec + " depende de " + names + ": se essa evidência estiver errada ou faltar, a decisão muda de natureza.";
            case "DEGRADED" -> !a.degraded().isEmpty()
                    ? "Decisão tomada com " + a.degraded().stream().map(d -> RiskModel.source(d).label().toLowerCase()).collect(Collectors.joining(", ")) + " indisponível; a confiança ficou menor."
                    : "A proteção se mantém, mas enfraquece sem " + names + ".";
            default -> "Confiança " + a.confidence() + ": não há evidência independente suficiente para sustentar a leitura.";
        };
        return new Certificate(status, summary, out, sensitiveTo);
    }

    public static Independence independence(Base a) {
        int favorableTotal = a.signals().stream().filter(s -> s.points() < 0).mapToInt(s -> -s.points()).sum();
        int againstTotal = a.signals().stream().filter(s -> s.points() > 0).mapToInt(Signal::points).sum();
        List<SourceGroup> groups = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean protective = RiskModel.isProtective(a.decision());
        for (RiskModel.Source spec : RiskModel.SOURCES) {
            List<Signal> sig = a.signals().stream().filter(s -> s.source().equals(spec.id())).toList();
            if (sig.isEmpty()) continue;
            int fav = sig.stream().filter(s -> s.points() < 0).mapToInt(s -> -s.points()).sum();
            int ag = sig.stream().filter(s -> s.points() > 0).mapToInt(Signal::points).sum();
            int shareFav = favorableTotal == 0 ? 0 : (int) Math.round(fav * 100.0 / favorableTotal);
            int shareAg = againstTotal == 0 ? 0 : (int) Math.round(ag * 100.0 / againstTotal);
            groups.add(new SourceGroup(spec.id(), spec.label(), sig.stream().mapToInt(Signal::points).sum(), shareFav, shareAg, sig.size()));
            if (sig.size() >= 3)
                warnings.add(sig.size() + " sinais vêm de " + spec.label().toLowerCase() + ": contam como uma única fonte na confiança.");
            if (shareFav >= 50 && favorableTotal >= 4 && !protective)
                warnings.add(shareFav + "% dos pontos a favor da aprovação vêm de " + spec.label().toLowerCase() + ".");
            if (spec.id().equals("customer") && shareAg >= 50 && protective)
                warnings.add("Mais da metade dos pontos contra a compra vem do que o próprio cliente declarou.");
        }
        for (String d : a.degraded()) warnings.add(RiskModel.source(d).label() + " estava indisponível nesta decisão.");
        return new Independence(groups.size(), a.signals().size(), groups, warnings);
    }
}
