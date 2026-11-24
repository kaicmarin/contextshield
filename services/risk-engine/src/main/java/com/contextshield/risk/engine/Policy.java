package com.contextshield.risk.engine;

import com.contextshield.risk.model.AssessmentInput.EngineConfig;
import com.contextshield.risk.model.Signal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Passos 2 e 3 do motor: soma os sinais (score + confiança) e aplica as regras R1 a R11. */
public final class Policy {
    private Policy() {}

    public record Scored(List<Signal> signals, int score, String band, String level, int confidence,
                         List<String> degraded) {
        boolean has(String id) {
            return signals.stream().anyMatch(s -> s.id().equals(id));
        }
    }

    public record Outcome(String decision, String ruleId, String ruleText) {}

    public static Scored score(List<Signal> raw, boolean discount, List<String> degraded, boolean verified) {
        return score(raw, discount, degraded, verified, Map.of());
    }

    public static Scored score(List<Signal> raw, boolean discount, List<String> degraded, boolean verified,
                               Map<String, Integer> weights) {
        Set<String> present = raw.stream().map(Signal::id).collect(Collectors.toSet());
        List<Signal> signals = raw.stream().map(s -> {
            String dep = RiskModel.signal(s.id()).dependsOn().stream().filter(present::contains).findFirst().orElse(null);
            if (dep == null || !discount) return s.withPoints(s.basePoints(), false, dep);
            // Math.round do JavaScript arredonda .5 para cima; Math.round do Java também (para positivos).
            int half = (int) Math.round(s.basePoints() / 2.0);
            return s.withPoints(half, true, dep);
        }).toList();

        int total = Weights.value(weights, "BASE_SCORE") + signals.stream().mapToInt(Signal::points).sum();
        int score = Math.max(0, Math.min(100, total));
        String band = band(score, weights);

        // Confiança: cada fonte independente vale 9 pontos; várias provas da mesma fonte contam como uma.
        Map<String, Integer> net = new HashMap<>();
        for (Signal s : signals) net.merge(s.source(), s.points(), Integer::sum);
        boolean conflict = net.values().stream().anyMatch(v -> v >= 10) && net.values().stream().anyMatch(v -> v <= -6);
        int confidence = 30 + 9 * net.size();
        if (verified) confidence += 10;
        if (conflict) confidence -= 10;
        confidence -= 12 * degraded.size();
        if (present.contains("HISTORY_SPARSE")) confidence -= 8;
        confidence = Math.max(15, Math.min(95, confidence));
        String level = confidence < RiskModel.MIN_CONFIDENCE ? "INCONCLUSIVE" : band;
        return new Scored(signals, score, band, level, confidence, degraded);
    }

    private static Outcome outcome(String ruleId, String decision, String why) {
        RiskModel.Rule r = RiskModel.rule(ruleId);
        return new Outcome(decision, ruleId, r.id() + " · " + r.name() + ": " + why);
    }

    private static String band(int score, Map<String, Integer> weights) {
        if (score >= Weights.value(weights, "THRESHOLD_BLOCK")) return "CRITICAL";
        if (score >= Weights.value(weights, "THRESHOLD_HIGH")) return "HIGH";
        if (score >= Weights.value(weights, "THRESHOLD_MEDIUM")) return "MEDIUM";
        return "LOW";
    }

    public static Outcome decide(Scored s, String stage, EngineConfig config) {
        boolean recalculated = "recalculated".equals(stage);
        int block = Weights.value(config.weights(), "THRESHOLD_BLOCK");
        int high = Weights.value(config.weights(), "THRESHOLD_HIGH");
        int medium = Weights.value(config.weights(), "THRESHOLD_MEDIUM");
        if (s.score() >= block) return outcome("R1", "BLOCK", "score " + s.score() + " na faixa muito alta");
        if (recalculated && config.flag("guidance-floor") && s.has("VER_ASKED") && s.has("VER_CONTACT"))
            return outcome("R2", "INTERVENE", "a verificação indica pedido de terceiro com contato prévio");
        if (recalculated && config.flag("script-mirror") && s.has("VER_COACHED"))
            return outcome("R9", "INTERVENE", "a pessoa contou que alguém orientou as respostas");
        if (recalculated && config.flag("script-mirror") && s.has("SCRIPT_ECHO"))
            return outcome("R10", "INTERVENE", "as respostas negam ajuda no mesmo formato que a mensagem pediu");
        if (recalculated && config.flag("script-mirror") && s.has("ANCHOR_FROM_SCRIPT"))
            return outcome("R11", "INTERVENE", "a resposta apontou o destino da compra como se fosse a casa do cadastro");
        if ("INCONCLUSIVE".equals(s.level())) {
            if (!recalculated)
                return s.score() >= medium
                        ? outcome("R3", "REQUEST_CONTEXT", "confiança " + s.confidence() + "; score " + s.score())
                        : outcome("R3", "APPROVE", "confiança " + s.confidence() + ", mas score baixo (" + s.score() + "): aprovar e monitorar");
            if (s.score() >= high)
                return outcome("R3", "INTERVENE", "confiança " + s.confidence() + " mesmo após contexto; score " + s.score());
            return outcome("R3", s.score() >= medium ? "APPROVE_WITH_ALERT" : "APPROVE",
                    "confiança " + s.confidence() + " após contexto; score " + s.score());
        }
        if ("HIGH".equals(s.band()))
            return recalculated
                    ? outcome("R4", "INTERVENE", "score " + s.score() + " depois da verificação")
                    : outcome("R5", "REQUEST_CONTEXT", "score " + s.score() + " sem contexto do cliente");
        if (!recalculated && config.flag("context-on-declared-guidance") && (s.has("ORIGIN_LINK") || s.has("ORIGIN_GUIDED")))
            return outcome("R6", "REQUEST_CONTEXT", "cliente declarou que a compra começou por link ou indicação");
        if ("MEDIUM".equals(s.band())) return outcome("R7", "APPROVE_WITH_ALERT", "score " + s.score());
        return outcome("R8", "APPROVE", "score " + s.score());
    }
}
