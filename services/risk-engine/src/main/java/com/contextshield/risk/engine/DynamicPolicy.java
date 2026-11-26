package com.contextshield.risk.engine;

import com.contextshield.risk.model.AssessmentInput.EngineConfig;
import com.contextshield.risk.model.DynamicRule;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Avalia regras publicadas na ordem de prioridade. Substitui o encadeamento fixo R1–R12. */
public final class DynamicPolicy {
    private DynamicPolicy() {}

    public static Policy.Outcome decide(Policy.Scored scored, String stage, EngineConfig config, List<DynamicRule> rules) {
        boolean recalculated = "recalculated".equals(stage);
        int block = Weights.value(config.weights(), "THRESHOLD_BLOCK");
        int high = Weights.value(config.weights(), "THRESHOLD_HIGH");
        int medium = Weights.value(config.weights(), "THRESHOLD_MEDIUM");
        List<DynamicRule> ordered = rules.stream()
                .sorted(Comparator.comparingInt(DynamicRule::priority).thenComparing(DynamicRule::key))
                .toList();
        for (DynamicRule rule : ordered) {
            String why = matches(rule, scored, recalculated, config, block, high, medium);
            if (why != null) {
                return outcome(rule, rule.outcome(), why);
            }
        }
        return new Policy.Outcome("REQUEST_CONTEXT", "S0",
                "S0 · Proteção: nenhuma regra casou; confirmação necessária antes de aprovar.");
    }

    private static Policy.Outcome outcome(DynamicRule rule, String decision, String why) {
        String id = rule.ruleId();
        String label = rule.name();
        if (label == null || label.isBlank()) {
            try {
                label = RiskModel.rule(id).name();
            } catch (RuntimeException ignored) {
                label = id;
            }
        }
        return new Policy.Outcome(decision, id, id + " · " + label + ": " + why);
    }

    @SuppressWarnings("unchecked")
    private static String matches(DynamicRule rule, Policy.Scored s, boolean recalculated, EngineConfig config,
                                  int block, int high, int medium) {
        Map<String, Object> p = rule.params() == null ? Map.of() : rule.params();
        return switch (rule.kind()) {
            case "SCORE_GTE_WEIGHT" -> {
                String weightKey = String.valueOf(p.getOrDefault("weightKey", "THRESHOLD_BLOCK"));
                int threshold = Weights.value(config.weights(), weightKey);
                if (s.score() >= threshold) yield "score " + s.score() + " na faixa muito alta";
                yield null;
            }
            case "RECALC_FLAG_SIGNALS" -> {
                if (!recalculated) yield null;
                if (p.containsKey("flag") && !config.flag(String.valueOf(p.get("flag")))) yield null;
                List<String> signals = (List<String>) p.get("signals");
                if (signals != null && signals.stream().allMatch(s::has)) {
                    yield reasonFor(rule.key(), s);
                }
                yield null;
            }
            case "LEVEL_INCONCLUSIVE_INITIAL_CTX" -> {
                if (recalculated || !"INCONCLUSIVE".equals(s.level())) yield null;
                int th = Weights.value(config.weights(), String.valueOf(p.getOrDefault("weightKey", "THRESHOLD_MEDIUM")));
                if (s.score() >= th) yield "confiança " + s.confidence() + "; score " + s.score();
                yield null;
            }
            case "LEVEL_INCONCLUSIVE_INITIAL_APPROVE" -> {
                if (recalculated || !"INCONCLUSIVE".equals(s.level())) yield null;
                if (s.score() >= medium) yield null;
                yield "confiança " + s.confidence() + ", mas score baixo (" + s.score() + "): aprovar e monitorar";
            }
            case "LEVEL_INCONCLUSIVE_RECALC_INTERVENE" -> {
                if (!recalculated || !"INCONCLUSIVE".equals(s.level())) yield null;
                int th = Weights.value(config.weights(), String.valueOf(p.getOrDefault("weightKey", "THRESHOLD_HIGH")));
                if (s.score() >= th) yield "confiança " + s.confidence() + " mesmo após contexto; score " + s.score();
                yield null;
            }
            case "LEVEL_INCONCLUSIVE_RECALC_ALERT" -> {
                if (!recalculated || !"INCONCLUSIVE".equals(s.level())) yield null;
                if (s.score() >= high) yield null;
                int th = Weights.value(config.weights(), String.valueOf(p.getOrDefault("weightKey", "THRESHOLD_MEDIUM")));
                if (s.score() >= th) yield "confiança " + s.confidence() + " após contexto; score " + s.score();
                yield null;
            }
            case "LEVEL_INCONCLUSIVE_RECALC_APPROVE" -> {
                if (!recalculated || !"INCONCLUSIVE".equals(s.level())) yield null;
                if (s.score() >= medium) yield null;
                yield "confiança " + s.confidence() + " após contexto; score " + s.score();
            }
            case "BAND_HIGH_RECALC" -> {
                if (!"HIGH".equals(s.band())) yield null;
                if (!recalculated) yield null;
                yield "score " + s.score() + " depois da verificação";
            }
            case "BAND_HIGH_INITIAL" -> {
                if (!"HIGH".equals(s.band())) yield null;
                if (recalculated) yield null;
                yield "score " + s.score() + " sem contexto do cliente";
            }
            case "ORIGIN_GUIDANCE_CTX" -> {
                if (recalculated) yield null;
                if (p.containsKey("flag") && !config.flag(String.valueOf(p.get("flag")))) yield null;
                List<String> signals = (List<String>) p.get("signals");
                if (signals != null && signals.stream().anyMatch(s::has))
                    yield "cliente declarou que a compra começou por link ou indicação";
                yield null;
            }
            case "BAND_MEDIUM_ALERT" -> {
                if (!"MEDIUM".equals(s.band())) yield null;
                yield "score " + s.score();
            }
            case "CONTEXT_PROBE" -> {
                if (recalculated) yield null;
                if (p.containsKey("flag") && !config.flag(String.valueOf(p.get("flag")))) yield null;
                if (!"LOW".equals(s.band())) yield null;
                if (!contextUnknown(s)) yield null;
                if (!probePresent(s, config)) yield null;
                yield "há um sinal de atenção já calculado e não há confirmação sobre contato de terceiro; a verificação seletiva foi pedida";
            }
            case "DEFAULT_APPROVE" -> "score " + s.score();
            case "SCORE_SIGNAL" -> scoreSignal(rule, s, recalculated, config, p);
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private static String scoreSignal(DynamicRule rule, Policy.Scored s, boolean recalculated, EngineConfig config,
                                      Map<String, Object> p) {
        String stage = String.valueOf(p.getOrDefault("stage", "any"));
        if ("initial".equals(stage) && recalculated) return null;
        if ("recalculated".equals(stage) && !recalculated) return null;
        int threshold;
        if (p.containsKey("weightKey")) {
            threshold = Weights.value(config.weights(), String.valueOf(p.get("weightKey")));
        } else {
            threshold = ((Number) p.getOrDefault("minScore", 0)).intValue();
        }
        if (s.score() < threshold) return null;
        Object raw = p.get("signal");
        if (raw instanceof String signal) {
            if (!s.has(signal)) return null;
        } else if (raw instanceof List<?> list) {
            if (list.stream().map(String::valueOf).noneMatch(s::has)) return null;
        }
        String summary = p.get("summary") == null ? "regra personalizada" : String.valueOf(p.get("summary"));
        return summary + "; score " + s.score();
    }

    private static String reasonFor(String key, Policy.Scored s) {
        return switch (key) {
            case "R2" -> "a verificação indica pedido de terceiro com contato prévio";
            case "R9" -> "a pessoa contou que alguém orientou as respostas";
            case "R10" -> "as respostas negam ajuda no mesmo formato que a mensagem pediu";
            case "R11" -> "a resposta apontou o destino da compra como se fosse a casa do cadastro";
            default -> "condição atendida";
        };
    }

    private static boolean contextUnknown(Policy.Scored s) {
        return !s.has("ORIGIN_LINK") && !s.has("ORIGIN_GUIDED")
                && !s.has("DESTINATION_IN_INCIDENT") && !s.has("SELLER_IN_INCIDENT");
    }

    private static boolean probePresent(Policy.Scored s, EngineConfig config) {
        List<String> requested = config.contextProbes();
        Set<String> probes = requested == null || requested.isEmpty()
                ? Policy.DEFAULT_PROBES
                : new HashSet<>(requested);
        if (probes.contains("NONE")) return false;
        return probes.stream().anyMatch(s::has);
    }
}