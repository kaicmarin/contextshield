package com.contextshield.core.web;

import com.contextshield.core.service.OrganizationService;
import com.contextshield.core.service.PartnerAssessmentService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Contrato versionado para outra aplicação usar o motor. O modelo não decide a operação. */
@RestController
@RequestMapping("/api/v1/partner")
public class PartnerController {

    private final PartnerAssessmentService partners;
    private final OrganizationService organizations;

    public PartnerController(PartnerAssessmentService partners, OrganizationService organizations) {
        this.partners = partners;
        this.organizations = organizations;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("service", "contextshield", "organization", organizations.current(Auth.user()), "decisionOwner", "risk-engine");
    }

    @PostMapping("/assessments")
    public Map<String, Object> assess(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                      @RequestBody Map<String, Object> body) {
        return partners.assess(Auth.user(), key, body);
    }

    @GetMapping("/assessments/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return partners.get(Auth.user(), id);
    }
}
