package com.contextshield.risk.engine;

import com.contextshield.risk.model.Assessment;
import com.contextshield.risk.model.AssessmentInput;
import com.contextshield.risk.model.Signal;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova de que o motor em Java decide igual ao protótipo em JavaScript.
 * parity-cases.json foi gerado rodando o motor original nos cenários da demo
 * (inclusive variações de resposta e fontes fora do ar). Cada caso vira um teste.
 */
class ParityTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final RiskAssessor assessor = new RiskAssessor();

    @TestFactory
    Stream<DynamicTest> javaEngineMatchesOriginalPrototype() throws Exception {
        JsonNode cases;
        try (InputStream in = getClass().getResourceAsStream("/parity-cases.json")) {
            cases = mapper.readTree(in);
        }
        List<DynamicTest> tests = new ArrayList<>();
        int i = 0;
        for (JsonNode c : cases) {
            String name = String.format("%02d %s · %s", i++, c.get("label").asText(), c.at("/input/stage").asText());
            tests.add(DynamicTest.dynamicTest(name, () -> {
                AssessmentInput input = mapper.treeToValue(c.get("input"), AssessmentInput.class);
                Assessment a = assessor.assess(input);
                JsonNode exp = c.get("expected");
                List<String> signals = a.signals().stream().map(s -> s.id() + ":" + s.points()).sorted().toList();
                List<String> expectedSignals = new ArrayList<>();
                exp.get("signals").forEach(n -> expectedSignals.add(n.asText()));
                assertThat(signals).as("sinais").isEqualTo(expectedSignals);
                assertThat(a.score()).as("score").isEqualTo(exp.get("score").asInt());
                assertThat(a.band()).as("faixa").isEqualTo(exp.get("band").asText());
                assertThat(a.level()).as("nível").isEqualTo(exp.get("level").asText());
                assertThat(a.confidence()).as("confiança").isEqualTo(exp.get("confidence").asInt());
                assertThat(a.proposedDecision()).as("decisão da política").isEqualTo(exp.get("proposedDecision").asText());
                assertThat(a.decision()).as("decisão final").isEqualTo(exp.get("decision").asText());
                assertThat(a.ruleId()).as("regra").isEqualTo(exp.get("ruleId").asText());
                assertThat(a.certificate().status()).as("fragilidade").isEqualTo(exp.get("fragility").asText());
            }));
        }
        return tests.stream();
    }

    static int sum(List<Signal> s) {
        return s.stream().mapToInt(Signal::points).sum();
    }
}
