package com.contextshield.risk.web;

import com.contextshield.risk.engine.RiskAssessor;
import com.contextshield.risk.engine.RiskModel;
import com.contextshield.risk.model.Assessment;
import com.contextshield.risk.model.AssessmentInput;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AssessmentController {

    private final RiskAssessor assessor;

    public AssessmentController(RiskAssessor assessor) {
        this.assessor = assessor;
    }

    /** Avalia uma compra. Não grava nada: quem persiste é a core-api. */
    @PostMapping("/assessments")
    public Assessment assess(@Valid @RequestBody AssessmentInput input) {
        return assessor.assess(input);
    }

    /** Catálogo público do motor: faixas, sinais, regras e chaves de configuração. */
    @GetMapping("/model")
    public Map<String, Object> model() {
        return Map.of(
                "engine", RiskModel.ENGINE_VERSION,
                "baseScore", RiskModel.BASE_SCORE,
                "minConfidence", RiskModel.MIN_CONFIDENCE,
                "bands", RiskModel.BANDS,
                "sources", RiskModel.SOURCES,
                "signals", RiskModel.SIGNALS,
                "rules", RiskModel.RULES,
                "decisions", RiskModel.DECISIONS,
                "flags", RiskModel.FLAGS);
    }
}
