package com.contextshield.risk.engine;

import com.contextshield.risk.model.Assessment.Certificate;
import com.contextshield.risk.model.Assessment.Independence;
import com.contextshield.risk.model.Assessment.Issue;
import com.contextshield.risk.model.Assessment.SafetyEnvelope;
import com.contextshield.risk.model.Signal;

import java.util.ArrayList;
import java.util.List;

/**
 * Envelope de segurança: roda depois da política e só pode endurecer a decisão, nunca afrouxar.
 * Ex.: a política aprovou, mas a aprovação depende de uma única fonte (frágil) → aprova com alerta.
 */
public final class SafetyMonitor {
    private SafetyMonitor() {}

    public static final String POLICY_VERSION = "safety-envelope-1";

    static String escalate(String decision) {
        int order = RiskModel.decision(decision).order();
        if (order <= 0) return "APPROVE_WITH_ALERT";
        if (order == 1) return "INTERVENE";
        return decision;
    }

    public static SafetyEnvelope evaluate(Robustness.Base a, String band, Certificate cert, Independence indep) {
        List<Issue> issues = new ArrayList<>();
        boolean liberating = !RiskModel.isProtective(a.decision());

        if (liberating && "INCONCLUSIVE".equals(a.level()) && "APPROVE".equals(a.decision()))
            issues.add(new Issue("INCONCLUSIVE_APPROVE", "Política propôs aprovação com nível inconclusivo; exige confirmação ou alerta.", "escalate"));
        if (liberating && a.confidence() < RiskModel.MIN_CONFIDENCE)
            issues.add(new Issue("LOW_CONFIDENCE", "Confiança " + a.confidence() + " abaixo do mínimo operacional (" + RiskModel.MIN_CONFIDENCE + ").", "escalate"));
        if (liberating && "FRAGILE".equals(cert.status()) && !onlySelectiveVerification(cert))
            issues.add(new Issue("FRAGILE_CERTIFICATE", cert.summary(), "escalate"));
        if (!a.degraded().isEmpty() && liberating && !"LOW".equals(band))
            issues.add(new Issue("DEGRADED_SOURCES", "Decisão de liberação com fontes indisponíveis: " + String.join(", ", a.degraded()) + ".", "warn"));
        indep.groups().stream()
                .filter(g -> liberating && g.shareFavorable() >= 55 && g.signals() >= 2)
                .findFirst()
                .ifPresent(g -> issues.add(new Issue("EVIDENCE_DOMINANCE",
                        "Mais de metade dos pontos favoráveis à liberação vêm de " + g.label().toLowerCase() + ".", "warn")));
        boolean intelAgainst = a.signals().stream().anyMatch(s ->
                (s.id().equals("DESTINATION_IN_INCIDENT") || s.id().equals("SELLER_IN_INCIDENT")) && s.points() > 0);
        if (liberating && intelAgainst)
            issues.add(new Issue("INTEL_MATCH", "Inteligência relaciona destino ou vendedor a ocorrência anterior; liberação automática bloqueada.", "block"));
        boolean unresolved = a.signals().stream().anyMatch(s -> s.id().equals("EVIDENCE_DIVERGENCE") && s.points() >= 6);
        if (liberating && unresolved)
            issues.add(new Issue("UNRESOLVED_DIVERGENCE", "Há divergência não resolvida entre contexto declarado e destino da compra.", "block"));

        String fin = a.decision();
        if (issues.stream().anyMatch(i -> i.severity().equals("block"))) fin = "BLOCK".equals(a.decision()) ? "BLOCK" : "INTERVENE";
        else if (issues.stream().anyMatch(i -> i.severity().equals("escalate"))) fin = escalate(a.decision());

        return new SafetyEnvelope(POLICY_VERSION, a.decision(), fin, fin.equals(a.decision()) && issues.isEmpty(), issues, cert.status());
    }

    /** Uma perturbação que só acionaria a verificação seletiva não transforma uma compra habitual em alerta. */
    private static boolean onlySelectiveVerification(Certificate cert) {
        List<com.contextshield.risk.model.Assessment.Perturbation> flips = cert.perturbations().stream()
                .filter(p -> p.crossesClass() || ("adulterada".equals(p.kind()) && p.changed()))
                .toList();
        return !flips.isEmpty() && flips.stream().allMatch(p -> "R12".equals(p.ruleId()) && "REQUEST_CONTEXT".equals(p.decision()));
    }

    static boolean hasSignal(List<Signal> signals, String id) {
        return signals.stream().anyMatch(s -> s.id().equals(id));
    }
}
