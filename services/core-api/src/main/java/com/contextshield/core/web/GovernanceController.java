package com.contextshield.core.web;

import com.contextshield.core.service.MlLabService;
import com.contextshield.core.service.RiskPolicyService;
import com.contextshield.core.service.RiskRuleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Aprovação, publicação e rótulos confirmados. O filtro de segurança exige papel ADMIN. */
@RestController
@RequestMapping("/api/admin")
public class GovernanceController {

    private final RiskPolicyService policies;
    private final RiskRuleService rules;
    private final MlLabService lab;

    public GovernanceController(RiskPolicyService policies, RiskRuleService rules, MlLabService lab) {
        this.policies = policies;
        this.rules = rules;
        this.lab = lab;
    }

    @PostMapping("/policies/{id}/approve")
    public Object approve(@PathVariable long id) {
        return policies.approve(id, Auth.user().email());
    }

    public record ReasonRequest(String reason) {}

    @PostMapping("/policies/{id}/reject")
    public Object reject(@PathVariable long id, @RequestBody(required = false) ReasonRequest req) {
        String reason = req == null || req.reason() == null || req.reason().isBlank() ? "Rejeitada" : req.reason();
        return policies.reject(id, reason, Auth.user().email());
    }

    @PostMapping("/policies/{id}/publish")
    public Object publish(@PathVariable long id) {
        return policies.publish(id, Auth.user().email());
    }

    @PostMapping("/policies/{id}/rollback")
    public Object rollback(@PathVariable long id) {
        return policies.rollback(id, Auth.user().email());
    }

    @PostMapping("/rules/{id}/approve")
    public Object approveRule(@PathVariable long id) {
        return rules.approve(id, Auth.user().email());
    }

    @PostMapping("/rules/{id}/reject")
    public Object rejectRule(@PathVariable long id, @RequestBody(required = false) ReasonRequest req) {
        String reason = req == null || req.reason() == null || req.reason().isBlank() ? "Rejeitada" : req.reason();
        return rules.reject(id, reason, Auth.user().email());
    }

    @PostMapping("/rules/{id}/publish")
    public Object publishRule(@PathVariable long id) {
        return rules.publish(id, Auth.user().email());
    }

    @PostMapping("/rules/{id}/rollback")
    public Object rollbackRule(@PathVariable long id) {
        return rules.rollback(id, Auth.user().email());
    }

    @PostMapping("/ml/models/{id}/approve")
    public Object approveModel(@PathVariable long id) {
        return lab.approveModel(id, Auth.user().email());
    }

    @PostMapping("/ml/models/{id}/decision")
    public Object refuseDecision(@PathVariable long id) {
        return lab.refuseDecisionUse(id, Auth.user().email());
    }

    @PostMapping("/ml/models/{id}/reject")
    public Object rejectModel(@PathVariable long id) {
        return lab.rejectModel(id, Auth.user().email());
    }

    @PostMapping("/ml/suggestions/{id}/draft")
    public Object draft(@PathVariable long id) {
        return lab.draftFromSuggestion(id, Auth.user().email());
    }

    @PostMapping("/ml/rule-suggestions/{id}/draft")
    public Object draftRule(@PathVariable long id) {
        return rules.draftFromMlSuggestion(id, Auth.user().email());
    }

    public record OutcomeRequest(@NotBlank String txId, @NotBlank String label) {}

    @PostMapping("/outcomes")
    public Object outcome(@Valid @RequestBody OutcomeRequest req) {
        return lab.recordOutcome(req.txId(), req.label(), Auth.user().email());
    }

    @PostMapping("/outcomes/reports")
    public Object report(@Valid @RequestBody OutcomeRequest req) {
        return lab.recordReport(req.txId(), req.label(), Auth.user().email());
    }
}
