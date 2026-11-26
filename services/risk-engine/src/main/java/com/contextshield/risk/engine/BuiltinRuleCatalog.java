package com.contextshield.risk.engine;

import com.contextshield.risk.model.DynamicRule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** R1–R12 equivalentes à lógica histórica do motor, para seed e testes de equivalência. */
public final class BuiltinRuleCatalog {
    private BuiltinRuleCatalog() {}

    public static List<DynamicRule> publishedDefaults() {
        List<DynamicRule> rules = new ArrayList<>();
        rules.add(rule("R1", "R1", "Risco muito alto", 10, "SCORE_GTE_WEIGHT",
                Map.of("weightKey", "THRESHOLD_BLOCK"), "BLOCK"));
        rules.add(rule("R2", "R2", "Orientação confirmada", 20, "RECALC_FLAG_SIGNALS",
                Map.of("flag", "guidance-floor", "signals", List.of("VER_ASKED", "VER_CONTACT")), "INTERVENE"));
        rules.add(rule("R9", "R9", "Questionário ensaiado", 25, "RECALC_FLAG_SIGNALS",
                Map.of("flag", "script-mirror", "signals", List.of("VER_COACHED")), "INTERVENE"));
        rules.add(rule("R10", "R10", "Espelho do roteiro", 26, "RECALC_FLAG_SIGNALS",
                Map.of("flag", "script-mirror", "signals", List.of("SCRIPT_ECHO")), "INTERVENE"));
        rules.add(rule("R11", "R11", "Endereço do roteiro", 27, "RECALC_FLAG_SIGNALS",
                Map.of("flag", "script-mirror", "signals", List.of("ANCHOR_FROM_SCRIPT")), "INTERVENE"));
        rules.add(rule("R3-1", "R3", "Confiança insuficiente", 31, "LEVEL_INCONCLUSIVE_INITIAL_CTX",
                Map.of("weightKey", "THRESHOLD_MEDIUM"), "REQUEST_CONTEXT"));
        rules.add(rule("R3-2", "R3", "Confiança insuficiente", 32, "LEVEL_INCONCLUSIVE_INITIAL_APPROVE",
                Map.of(), "APPROVE"));
        rules.add(rule("R3-3", "R3", "Confiança insuficiente", 33, "LEVEL_INCONCLUSIVE_RECALC_INTERVENE",
                Map.of("weightKey", "THRESHOLD_HIGH"), "INTERVENE"));
        rules.add(rule("R3-4", "R3", "Confiança insuficiente", 34, "LEVEL_INCONCLUSIVE_RECALC_ALERT",
                Map.of("weightKey", "THRESHOLD_MEDIUM"), "APPROVE_WITH_ALERT"));
        rules.add(rule("R3-5", "R3", "Confiança insuficiente", 35, "LEVEL_INCONCLUSIVE_RECALC_APPROVE",
                Map.of(), "APPROVE"));
        rules.add(rule("R4", "R4", "Risco alto após contexto", 40, "BAND_HIGH_RECALC", Map.of(), "INTERVENE"));
        rules.add(rule("R5", "R5", "Risco alto sem contexto", 41, "BAND_HIGH_INITIAL", Map.of(), "REQUEST_CONTEXT"));
        rules.add(rule("R6", "R6", "Orientação declarada", 42, "ORIGIN_GUIDANCE_CTX",
                Map.of("flag", "context-on-declared-guidance", "signals", List.of("ORIGIN_LINK", "ORIGIN_GUIDED")),
                "REQUEST_CONTEXT"));
        rules.add(rule("R7", "R7", "Risco médio", 50, "BAND_MEDIUM_ALERT", Map.of(), "APPROVE_WITH_ALERT"));
        rules.add(rule("R12", "R12", "Contexto desconhecido", 60, "CONTEXT_PROBE",
                Map.of("flag", "context-when-unknown"), "REQUEST_CONTEXT"));
        rules.add(rule("R8", "R8", "Risco baixo", 900, "DEFAULT_APPROVE", Map.of(), "APPROVE"));
        return rules;
    }

    private static DynamicRule rule(String key, String display, String name, int priority, String kind,
                                    Map<String, Object> params, String outcome) {
        var copy = new LinkedHashMap<String, Object>();
        if (params != null) copy.putAll(params);
        return new DynamicRule(key, display, name, "", priority, kind, copy, outcome);
    }
}