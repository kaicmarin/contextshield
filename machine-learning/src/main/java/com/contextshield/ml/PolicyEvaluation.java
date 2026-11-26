package com.contextshield.ml;

import com.contextshield.risk.engine.RiskAssessor;
import com.contextshield.risk.model.Assessment;
import com.contextshield.risk.model.AssessmentInput;
import com.contextshield.risk.model.DynamicRule;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Avalia pesos + regras dinâmicas contra rótulos confirmados ou sintéticos. Não publica nada. */
public final class PolicyEvaluation {
    private PolicyEvaluation() {}

    public record CostProfile(int falseNegativeCost, int falsePositiveCost, String id, String description) {
        public static CostProfile standard() {
            return new CostProfile(WeightSearch.FALSE_NEGATIVE_COST, WeightSearch.FALSE_POSITIVE_COST,
                    "standard", WeightSearch.FALSE_NEGATIVE_COST + "×FN + " + WeightSearch.FALSE_POSITIVE_COST + "×FP");
        }
    }

    public record CaseRef(String ref, boolean fraud, AssessmentInput initial, AssessmentInput.Answers answers) {}

    public static Map<String, Object> scoreCases(List<CaseRef> cases, Map<String, Integer> weights, List<DynamicRule> rules,
                                                 CostProfile cost) {
        RiskAssessor assessor = new RiskAssessor();
        AssessmentInput.EngineConfig base = AssessmentInput.EngineConfig.defaults();
        var config = new AssessmentInput.EngineConfig(base.flags(), base.providers(), weights, base.contextProbes(), rules);
        int fp = 0;
        int fn = 0;
        Map<String, Integer> ruleHits = new LinkedHashMap<>();
        Map<String, Integer> signalTally = new LinkedHashMap<>();
        List<Map<String, Object>> mistakes = new ArrayList<>();
        for (CaseRef item : cases) {
            Assessment first = assessor.assess(item.initial().withConfig(config));
            String decision = first.decision();
            String ruleId = first.ruleId();
            if ("REQUEST_CONTEXT".equals(decision) && item.answers() != null) {
                Assessment again = assessor.assess(item.initial().withConfig(config).withStage("recalculated", item.answers()));
                decision = again.decision();
                ruleId = again.ruleId();
            }
            ruleHits.merge(ruleId == null ? "—" : ruleId, 1, Integer::sum);
            boolean released = "APPROVE".equals(decision) || "APPROVE_WITH_ALERT".equals(decision);
            boolean wrong = (item.fraud() && released) || (!item.fraud() && !released);
            if (item.fraud() && released) fn++;
            if (!item.fraud() && !released) fp++;
            if (wrong && mistakes.size() < 12) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ref", item.ref());
                row.put("label", item.fraud() ? "FRAUD" : "LEGIT");
                row.put("decision", decision);
                row.put("ruleId", ruleId);
                mistakes.add(row);
            }
            first.signals().forEach(s -> signalTally.merge(s.id(), 1, Integer::sum));
        }
        int totalCost = cost.falseNegativeCost() * fn + cost.falsePositiveCost() * fp;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("examples", cases.size());
        out.put("falsePositives", fp);
        out.put("falseNegatives", fn);
        out.put("cost", totalCost);
        out.put("costProfile", Map.of("id", cost.id(), "description", cost.description(),
                "falseNegativeCost", cost.falseNegativeCost(), "falsePositiveCost", cost.falsePositiveCost()));
        out.put("ruleHits", ruleHits);
        out.put("topSignals", signalTally.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(8)
                .map(e -> Map.of("signal", e.getKey(), "count", e.getValue()))
                .toList());
        out.put("sampleMistakes", mistakes);
        return out;
    }

    public static List<CaseRef> syntheticReady(List<SyntheticCases.Case> cases, ObjectMapper mapper) {
        List<CaseRef> out = new ArrayList<>();
        for (SyntheticCases.Case item : cases) {
            if ("INCONCLUSIVE".equals(item.label())) continue;
            AssessmentInput input = mapper.convertValue(item.checkout(), AssessmentInput.class);
            AssessmentInput.Answers answers = item.answers() == null ? null
                    : mapper.convertValue(item.answers(), AssessmentInput.Answers.class);
            out.add(new CaseRef(item.story(), "FRAUD".equals(item.label()), input, answers));
        }
        return out;
    }

    public static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}