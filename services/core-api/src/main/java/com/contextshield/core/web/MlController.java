package com.contextshield.core.web;

import com.contextshield.core.service.MlLabService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Laboratório visível ao analista. Aprovar modelo e gravar rótulo exigem administração. */
@RestController
@RequestMapping("/api/ops/ml")
public class MlController {

    private final MlLabService lab;

    public MlController(MlLabService lab) {
        this.lab = lab;
    }

    @GetMapping
    public Map<String, Object> overview() {
        return lab.overview();
    }

    @GetMapping("/experiments/{id}")
    public Map<String, Object> experiment(@PathVariable long id) {
        return lab.experiment(id);
    }

    public record ExperimentRequest(int examples, long seed) {}

    @PostMapping("/experiments")
    public Map<String, Object> queue(@RequestBody ExperimentRequest req) {
        return lab.queue(req.examples(), req.seed(), Auth.user().email());
    }
}
