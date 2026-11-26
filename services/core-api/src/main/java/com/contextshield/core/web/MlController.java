package com.contextshield.core.web;

import com.contextshield.core.service.LabStudyService;
import com.contextshield.core.service.MlLabService;
import com.contextshield.core.service.OrganizationService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Laboratório visível ao analista. Aprovar modelo e gravar rótulo exigem administração. */
@RestController
@RequestMapping("/api/ops/ml")
public class MlController {

    private final MlLabService lab;
    private final LabStudyService studies;
    private final OrganizationService organizations;

    public MlController(MlLabService lab, LabStudyService studies, OrganizationService organizations) {
        this.lab = lab;
        this.studies = studies;
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

    @GetMapping("/labels")
    public Map<String, Object> labels() {
        return studies.labelsOverview(organizations.idOf(Auth.user()));
    }

    @GetMapping("/studies")
    public Object studies() {
        return studies.studies(organizations.idOf(Auth.user()));
    }

    @GetMapping("/studies/{id}")
    public Map<String, Object> study(@PathVariable long id) {
        return studies.study(id, organizations.idOf(Auth.user()));
    }

    public record WeightStudyRequest(Long seed, String costProfile) {}

    @PostMapping("/studies/weights")
    public Map<String, Object> weightStudy(@RequestBody(required = false) WeightStudyRequest req) {
        long seed = req == null || req.seed() == null ? 42L : req.seed();
        String cost = req == null || req.costProfile() == null ? "standard" : req.costProfile();
        return studies.queueWeightStudy(organizations.idOf(Auth.user()), Auth.user().email(), seed, cost);
    }

    @PostMapping("/studies/rules")
    public Map<String, Object> ruleStudy() {
        return studies.queueRuleStudy(organizations.idOf(Auth.user()), Auth.user().email());
    }
}
