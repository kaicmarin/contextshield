package com.contextshield.mlservice.web;

import com.contextshield.ml.ContextFeatures;
import com.contextshield.ml.Features;
import com.contextshield.ml.SavedModel;
import com.contextshield.mlservice.ModelCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/internal/v1")
public class PredictionController {
    private final ModelCatalog catalog;

    public PredictionController(ModelCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/models")
    public Map<String, Object> models() throws IOException {
        return Map.of("files", catalog.files(), "decidesPurchase", false);
    }

    /** A resposta nunca autoriza a compra, mesmo que o corpo peça o contrário. */
    @PostMapping("/predictions")
    public Map<String, Object> predict(@RequestBody Map<String, Object> body) {
        String file = String.valueOf(body.get("modelFile"));
        if (!(body.get("checkout") instanceof Map<?, ?> checkout)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Compra ausente.");
        }
        boolean contextual = Boolean.TRUE.equals(body.get("contextual"));
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) checkout;
        long started = System.nanoTime();
        SavedModel.Prediction prediction = catalog.open(file).predict(contextual ? ContextFeatures.vector(row) : Features.vector(row));
        double ms = (System.nanoTime() - started) / 1_000_000.0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("algorithm", prediction.algorithm());
        out.put("prediction", prediction.label());
        out.put("probability", prediction.fraudProbability());
        out.put("latencyMs", Math.round(ms * 1000.0) / 1000.0);
        out.put("affectsDecision", false);
        out.put("mode", "shadow");
        return out;
    }

    /** Treino não entra no caminho da compra. O processo Java de treino continua separado. */
    @PostMapping("/training")
    public Map<String, Object> training() {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "O treino não roda dentro da transação. Use o processo Java sob demanda e preserve os arquivos atuais.");
    }
}
