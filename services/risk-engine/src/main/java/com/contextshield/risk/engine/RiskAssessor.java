package com.contextshield.risk.engine;

import com.contextshield.risk.model.Assessment;
import com.contextshield.risk.model.AssessmentInput;
import com.contextshield.risk.model.Signal;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Orquestra o motor: sinais → score e confiança → política (R1–R11) → certificado de fragilidade
 * → Safety Monitor → perguntas de verificação (quando a decisão é pedir contexto).
 */
@Service
public class RiskAssessor {

    public Assessment assess(AssessmentInput in) {
        long t0 = System.nanoTime();
        var cfg = in.configOrDefault();
        List<String> degraded = SignalCollector.downProviders(in);
        List<Signal> raw = SignalCollector.collect(in);
        Policy.Scored scored = Policy.score(raw, cfg.flag("dependency-discount"), degraded,
                in.recalculated() && in.answers() != null, cfg.weights());
        Policy.Outcome policy = Policy.decide(scored, in.stage(), cfg);

        Robustness.Base base = new Robustness.Base(in.stage(), scored.signals(), policy.decision(), scored.level(),
                scored.score(), scored.confidence(), degraded);
        Assessment.Certificate cert = Robustness.certificate(base, cfg);
        Assessment.Independence indep = Robustness.independence(base);
        Assessment.SafetyEnvelope safety = SafetyMonitor.evaluate(base, scored.band(), cert, indep);

        String decision = safety.finalDecision();
        String ruleId = policy.ruleId();
        String ruleText = policy.ruleText();
        if (!decision.equals(policy.decision())) {
            ruleId = "S-" + ruleId;
            ruleText = ruleText + " | Safety Monitor (" + safety.policyVersion() + "): "
                    + safety.issues().stream().map(Assessment.Issue::message).collect(Collectors.joining(" · "))
                    + " → " + RiskModel.decision(decision).label();
        }

        Assessment.QuestionPlan plan = "REQUEST_CONTEXT".equals(decision) ? QuestionPlanner.plan(in, scored.signals()) : null;
        double ms = Math.max(0.01, Math.round((System.nanoTime() - t0) / 10_000.0) / 100.0);
        return new Assessment(in.stage(), in.at(), scored.score(), scored.band(), scored.level(), scored.confidence(),
                policy.decision(), decision, ruleId, ruleText, scored.signals(), degraded, safety, cert, indep, plan,
                ms, RiskModel.ENGINE_VERSION);
    }
}
