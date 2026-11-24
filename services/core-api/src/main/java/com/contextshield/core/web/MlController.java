package com.contextshield.core.web;

import com.contextshield.core.service.MlLabService;
import com.contextshield.core.service.OrganizationService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Laboratório visível ao analista. Aprovar modelo e gravar rótulo exigem administração. */
@RestController
@RequestMapping("/api/ops/ml")
public class MlController {

    private final MlLabService lab;
    private final OrganizationService organizations;

    public MlController(MlLabService lab, OrganizationService organizations) {
        this.lab = lab;
        this.organizations = organizations;
    }

    @GetMapping
    public Map<String, Object> overview() {
        return lab.overview(organizations.idOf(Auth.user()));
    }

    @GetMapping("/monitor")
    public Map<String, Object> monitor() {
        return lab.monitor(organizations.idOf(Auth.user()));
    }

    @GetMapping("/experiments/{id}")
    public Map<String, Object> experiment(@PathVariable long id) {
        return lab.experiment(id, organizations.idOf(Auth.user()));
    }

    public record ExperimentRequest(int examples, long seed) {}

    @PostMapping("/experiments")
    public Map<String, Object> queue(@RequestBody ExperimentRequest req) {
        return lab.queue(req.examples(), req.seed(), Auth.user().email());
    }
}
