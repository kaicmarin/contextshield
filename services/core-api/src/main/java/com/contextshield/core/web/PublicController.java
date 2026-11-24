package com.contextshield.core.web;

import com.contextshield.core.service.CheckService;
import com.contextshield.core.service.RiskEngineClient;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

/** Rotas públicas: verificador "Confira antes de pagar" e catálogo de regras do motor. */
@RestController
@RequestMapping("/api")
public class PublicController {

    private final CheckService check;
    private final RiskEngineClient engine;

    public PublicController(CheckService check, RiskEngineClient engine) {
        this.check = check;
        this.engine = engine;
    }

    public record CheckRequest(@NotBlank @Size(max = 4000) String text) {}

    @Operation(summary = "Confira antes de pagar", description = "Analisa uma mensagem colada pelo cliente. Não abre links. O score é só deste verificador, separado do Risk Engine. Texto vazio ou acima de 4000 caracteres responde 400. Acima do teto por IP responde 429. A mensagem não é gravada no log.")
    @ApiResponse(responseCode = "200", description = "Classificação. level: RED, YELLOW, GRAY ou GREEN. verdict: alto, atencao, insuficiente ou baixo.")
    @ApiResponse(responseCode = "400", description = "Texto ausente, vazio ou longo demais.", content = @Content(examples = @ExampleObject(value = "{\"status\":400,\"error\":\"Alguns campos precisam de correção\",\"fields\":{\"text\":\"não deve estar em branco\"}}")))
    @ApiResponse(responseCode = "429", description = "Muitas consultas no mesmo minuto.", content = @Content(examples = @ExampleObject(value = "{\"status\":429,\"error\":\"Muitas consultas em pouco tempo. Espere um minuto e tente de novo.\"}")))
    @PostMapping("/check")
    public CheckService.Result check(@Valid @RequestBody CheckRequest req) {
        return check.analyze(req.text());
    }

    @GetMapping("/engine/model")
    public JsonNode model() {
        return engine.model();
    }
}
