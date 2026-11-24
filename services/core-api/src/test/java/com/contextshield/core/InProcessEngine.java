package com.contextshield.core;

import com.contextshield.core.config.AppProperties;
import com.contextshield.core.config.JwtService;
import com.contextshield.core.service.RiskEngineClient;
import com.contextshield.risk.engine.RiskAssessor;
import com.contextshield.risk.model.AssessmentInput;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/** Substitui a chamada HTTP pelo motor real em memória. Pode simular o motor fora do ar. */
public class InProcessEngine extends RiskEngineClient {
    private final RiskAssessor assessor = new RiskAssessor();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    public volatile boolean down = false;

    public InProcessEngine(AppProperties props, JwtService jwt) {
        super(props, jwt);
    }

    @Override
    public JsonNode assess(Map<String, Object> input) {
        if (down) throw new EngineUnavailable();
        AssessmentInput in = mapper.convertValue(input, AssessmentInput.class);
        return mapper.valueToTree(assessor.assess(in));
    }
}
