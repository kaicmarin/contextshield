package com.contextshield.core.web;

import com.contextshield.core.service.LabStudyService;
import com.contextshield.core.service.MlActivityService;
import com.contextshield.core.service.MlLabService;
import com.contextshield.core.service.OrganizationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/** Laboratório visível ao analista. Aprovar modelo e gravar rótulo exigem administração. */
@RestController
@RequestMapping("/api/ops/ml")
public class MlController {

    private final MlLabService lab;
    private final LabStudyService studies;
    private final MlActivityService activity;
    private final OrganizationService organizations;

    public MlController(MlLabService lab, LabStudyService studies, MlActivityService activity, OrganizationService organizations) {
        this.lab = lab;
        this.studies = studies;
        this.activity = activity;
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

    @GetMapping("/activity/summary")
    public Map<String, Object> activitySummary(@RequestParam(defaultValue = "7") int days) {
        return activity.summary(organizations.idOf(Auth.user()), days);
    }

    @GetMapping("/activity")
    public Map<String, Object> activityFeed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Long modelId,
            @RequestParam(required = false) String algorithm,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String prediction,
            @RequestParam(required = false) String align,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "false") boolean live) {
        long org = organizations.idOf(Auth.user());
        var query = new MlActivityService.ActivityQuery(page, size, cursor, modelId, algorithm, eventType, prediction,
                align, stage, status, q, from, to, live);
        return activity.activity(org, query);
    }

    @GetMapping("/models/{id}/activity")
    public Map<String, Object> modelActivity(@PathVariable long id) {
        return activity.modelActivity(organizations.idOf(Auth.user()), id);
    }
}
