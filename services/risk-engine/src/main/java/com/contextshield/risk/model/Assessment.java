package com.contextshield.risk.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/** Resposta do motor para uma compra em um estágio (primeira leitura ou depois da verificação). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Assessment(
        String stage,
        LocalDateTime at,
        int score,
        String band,
        String level,
        int confidence,
        String proposedDecision,  // o que a política (R1 a R11) decidiu
        String decision,          // decisão final, depois do Safety Monitor
        String ruleId,
        String ruleText,
        List<Signal> signals,
        List<String> degraded,
        SafetyEnvelope safety,
        Certificate certificate,
        Independence independence,
        QuestionPlan verification,
        double durationMs,
        String engine
) {
    public record SafetyEnvelope(String policyVersion, String proposed, String finalDecision,
                                 boolean withinEnvelope, List<Issue> issues, String fragility) {}

    public record Issue(String code, String message, String severity) {}

    public record Certificate(String status, String summary, List<Perturbation> perturbations,
                              List<String> sensitiveTo) {}

    public record Perturbation(String id, String source, String kind, String label, int score,
                               String level, String decision, String ruleId, boolean changed,
                               boolean crossesClass) {}

    public record Independence(int independentSources, int totalSignals, List<SourceGroup> groups,
                               List<String> warnings) {}

    public record SourceGroup(String source, String label, int net, int shareFavorable, int shareAgainst,
                              int signals) {}

    public record QuestionPlan(String situation, List<Question> questions, List<Trigger> fired,
                               List<Trigger> quiet) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Question(String key, String triggerId, String hypothesis, String prompt, String help,
                           String quote, boolean showAddress, List<Option> options) {}

    public record Option(String id, String label) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Trigger(String id, String name, String reason, boolean pulled, String heldBecause) {}
}
