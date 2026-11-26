package com.contextshield.core.web;

import com.contextshield.core.service.OrganizationService;
import com.contextshield.core.service.RiskRuleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ops/rules")
public class RuleController {

    private final RiskRuleService rules;
    private final OrganizationService organizations;

    public RuleController(RiskRuleService rules, OrganizationService organizations) {
        this.rules = rules;
        this.organizations = organizations;
    }

    @GetMapping
    public Object list() {
        return rules.list(organizations.idOf(Auth.user()));
    }

    @GetMapping("/catalog")
    public Object catalog() {
        return rules.catalog(organizations.idOf(Auth.user()));
    }

    @GetMapping("/{id}")
    public Object get(@PathVariable long id) {
        return rules.get(id, organizations.idOf(Auth.user()));
    }

    public record CreateRequest(
            @NotBlank String ruleKey,
            @NotBlank String name,
            String summary,
            int priority,
            @NotBlank String kind,
            Map<String, Object> params,
            @NotBlank String outcome,
            String note
    ) {}

    @PostMapping
    public Object create(@Valid @RequestBody CreateRequest req) {
        return rules.create(req.ruleKey(), req.name(), req.summary(), req.priority(), req.kind(),
                req.params() == null ? Map.of() : req.params(), req.outcome(), req.note(), Auth.user().email());
    }

    public record EditRequest(
            @NotBlank String name,
            String summary,
            int priority,
            @NotBlank String kind,
            Map<String, Object> params,
            @NotBlank String outcome,
            String note
    ) {}

    @PutMapping("/{id}")
    public Object update(@PathVariable long id, @Valid @RequestBody EditRequest req) {
        return rules.update(id, req.name(), req.summary(), req.priority(), req.kind(),
                req.params() == null ? Map.of() : req.params(), req.outcome(), req.note(), Auth.user().email());
    }

    @PostMapping("/{id}/version")
    public Object newVersion(@PathVariable long id) {
        return rules.newVersion(id, Auth.user().email());
    }

    @PostMapping("/{id}/submit")
    public Object submit(@PathVariable long id) {
        return rules.submit(id, Auth.user().email());
    }

    @PostMapping("/{id}/disable")
    public Object disable(@PathVariable long id) {
        return rules.disable(id, Auth.user().email());
    }

    @PostMapping("/{id}/enable")
    public Object enable(@PathVariable long id) {
        return rules.enable(id, Auth.user().email());
    }
}