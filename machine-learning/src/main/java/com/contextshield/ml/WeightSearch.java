package com.contextshield.ml;

import com.contextshield.risk.engine.RiskAssessor;
import com.contextshield.risk.engine.Weights;
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
import java.util.Random;

/**
 * Busca aleatória estruturada. Cada candidato passa pelo RiskAssessor de verdade.
 * O rótulo é o do gerador (ou um caso confirmado), nunca o score do próprio motor.
 * Nada aqui publica a política.
 */
public final class WeightSearch {
    private WeightSearch() {}

    public static final int CANDIDATES = 36;
    public static final int FALSE_NEGATIVE_COST = 5;
    public static final int FALSE_POSITIVE_COST = 1;

    public static Map<String, Object> run(List<SyntheticCases.Case> search, List<SyntheticCases.Case> confirm, long seed) {
        return run(search, confirm, seed, CANDIDATES);
    }

    public static Map<String, Object> run(List<SyntheticCases.Case> search, List<SyntheticCases.Case> confirm, long seed, int requested) {
        return run(search, confirm, seed, requested, List.of(), PolicyEvaluation.CostProfile.standard());
    }

    public static Map<String, Object> run(List<SyntheticCases.Case> search, List<SyntheticCases.Case> confirm, long seed,
                                          int requested, List<DynamicRule> rules, PolicyEvaluation.CostProfile cost) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        RiskAssessor assessor = new RiskAssessor();
        List<Ready> searchReady = ready(mapper, search);
        List<Ready> confirmReady = ready(mapper, confirm);
        Score baselineSearch = score(assessor, searchReady, Map.of(), rules, cost);
        Score baselineConfirm = score(assessor, confirmReady, Map.of(), rules, cost);

        Random random = new Random(seed);
        List<String> keys = Weights.CATALOG.stream().map(Weights.Bound::key).toList();
        Map<String, Integer> best = Map.of();
        Score bestSearch = baselineSearch;
        int evaluated = 0;
        int rounds = Math.max(8, Math.min(requested, 80));
        for (int candidate = 0; candidate < rounds; candidate++) {
            Map<String, Integer> overlay = new LinkedHashMap<>();
            int changes = 3 + random.nextInt(4);
            for (int change = 0; change < changes; change++) {
                String key = keys.get(random.nextInt(keys.size()));
                int current = Weights.value(overlay, key);
                int step = random.nextInt(5) == 0 ? 2 : 1;
                int next = current + (random.nextBoolean() ? step : -step);
                overlay.put(key, Weights.value(Map.of(key, next), key));
            }
            Score found = score(assessor, searchReady, overlay, rules, cost);
            evaluated++;
            if (found.cost < bestSearch.cost) {
                bestSearch = found;
                best = Map.copyOf(overlay);
            }
        }
        Score bestConfirm = score(assessor, confirmReady, best, rules, cost);
        boolean improved = bestSearch.cost < baselineSearch.cost
                && bestConfirm.cost <= baselineConfirm.cost
                && bestConfirm.falseNegatives <= baselineConfirm.falseNegatives;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("method", "busca aleatória estruturada");
        out.put("candidates", evaluated);
        out.put("cost", cost.description());
        out.put("costProfileId", cost.id());
        out.put("dynamicRulesApplied", !rules.isEmpty());
        out.put("definition", "Falso negativo: fraude liberada (aprovada ou aprovada com alerta). Falso positivo: operação legítima não liberada.");
        out.put("searchExamples", searchReady.size());
        out.put("confirmExamples", confirmReady.size());
        out.put("baselineSearch", baselineSearch.toMap());
        out.put("baselineConfirm", baselineConfirm.toMap());
        out.put("bestSearch", bestSearch.toMap());
        out.put("bestConfirm", bestConfirm.toMap());
        out.put("improvedOnBothSets", improved);
        out.put("suggestedWeights", improved ? best : Map.of());
        out.put("requestedCandidates", requested);
        out.put("independentConfirm", true);
        boolean negativesFell = bestConfirm.falseNegatives < baselineConfirm.falseNegatives;
        boolean positivesFell = bestConfirm.falsePositives < baselineConfirm.falsePositives;
        out.put("published", false);
        out.put("falseNegativesFellOnConfirm", negativesFell);
        out.put("falsePositivesFellOnConfirm", positivesFell);
        out.put("criticalFloorsRespected", true);
        out.put("reasons", reasons(improved ? best : Map.of()));
        String note = improved
                ? "A sugestão reduziu o custo na busca e não piorou falso negativo nem custo na confirmação. Ela não foi publicada."
                : "Nenhuma candidata melhorou a busca e se manteve na confirmação. A política ativa não muda.";
        if (improved && positivesFell && !negativesFell)
            note += " O falso positivo caiu na confirmação e o falso negativo não.";
        out.put("note", note);
        return out;
    }

    private static List<Map<String, Object>> reasons(Map<String, Integer> overlay) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Integer> change : overlay.entrySet()) {
            Weights.Bound bound = Weights.bound(change.getKey());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", change.getKey());
            row.put("from", bound.fallback());
            row.put("to", change.getValue());
            row.put("safetyFloor", bound.safetyFloor());
            row.put("why", bound.detail() + " O valor ficou dentro do limite " + bound.min() + "–" + bound.max() + ".");
            out.add(row);
        }
        return out;
    }

    private record Ready(AssessmentInput initial, AssessmentInput.Answers answers, boolean fraud) {}

    private static List<Ready> ready(ObjectMapper mapper, List<SyntheticCases.Case> cases) {
        List<Ready> out = new ArrayList<>();
        for (SyntheticCases.Case item : cases) {
            if ("INCONCLUSIVE".equals(item.label())) continue;
            AssessmentInput input = mapper.convertValue(item.checkout(), AssessmentInput.class);
            AssessmentInput.Answers answers = item.answers() == null ? null : mapper.convertValue(item.answers(), AssessmentInput.Answers.class);
            out.add(new Ready(input, answers, "FRAUD".equals(item.label())));
        }
        return out;
    }

    private static Score score(RiskAssessor assessor, List<Ready> cases, Map<String, Integer> weights,
                               List<DynamicRule> rules, PolicyEvaluation.CostProfile cost) {
        var base = AssessmentInput.EngineConfig.defaults();
        var config = new AssessmentInput.EngineConfig(base.flags(), base.providers(), weights, base.contextProbes(), rules);
        int fp = 0;
        int fn = 0;
        for (Ready item : cases) {
            Assessment first = assessor.assess(item.initial.withConfig(config));
            String decision = first.decision();
            if ("REQUEST_CONTEXT".equals(decision) && item.answers != null) {
                decision = assessor.assess(item.initial.withConfig(config).withStage("recalculated", item.answers)).decision();
            }
            boolean released = "APPROVE".equals(decision) || "APPROVE_WITH_ALERT".equals(decision);
            if (item.fraud && released) fn++;
            if (!item.fraud && !released) fp++;
        }
        return new Score(fp, fn, cost.falseNegativeCost() * fn + cost.falsePositiveCost() * fp);
    }

    private record Score(int falsePositives, int falseNegatives, int cost) {
        Map<String, Object> toMap() {
            return Map.of("falsePositives", falsePositives, "falseNegatives", falseNegatives, "cost", cost);
        }
    }
}
