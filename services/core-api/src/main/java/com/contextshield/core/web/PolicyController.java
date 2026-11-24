package com.contextshield.core.web;

import com.contextshield.core.service.OrganizationService;
import com.contextshield.core.service.PolicySimulation;
import com.contextshield.core.service.RiskPolicyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Consulta e rascunho de políticas. Publicar fica em /api/admin e exige administração. */
@RestController
@RequestMapping("/api/ops/policies")
public class PolicyController {

    private final RiskPolicyService policies;
    private final PolicySimulation simulation;
    private final OrganizationService organizations;

    public PolicyController(RiskPolicyService policies, PolicySimulation simulation, OrganizationService organizations) {
        this.policies = policies;
        this.simulation = simulation;
        this.organizations = organizations;
    }

    @GetMapping
    public Object list() {
        return policies.list(organizations.idOf(Auth.user()));
    }

    @GetMapping("/catalog")
    public Object catalog() {
        return Map.of("profile", policies.activeProfile(), "signals", policies.catalog(organizations.idOf(Auth.user())),
                "rules", "As regras R1 a R11 continuam fixas. R12 fica desligada por padrão e, quando ligada, só pede verificação se já houver um sinal de atenção. O score não muda.");
    }

    @GetMapping("/compare")
    public Object compare(@RequestParam long left, @RequestParam long right) {
        return policies.compare(left, right, organizations.idOf(Auth.user()));
    }

    @GetMapping("/{id}")
    public Object get(@PathVariable long id) {
        return policies.get(id, organizations.idOf(Auth.user()));
    }

    public record DraftRequest(@NotBlank String name, String profile, Map<String, Integer> weights, String note) {}

    @PostMapping
    public Object create(@Valid @RequestBody DraftRequest req) {
        return policies.create(req.name(), req.profile(), req.weights(), req.note(), Auth.user().email());
    }

    public record EditRequest(Map<String, Integer> weights, String note) {}

    @PutMapping("/{id}")
    public Object update(@PathVariable long id, @RequestBody EditRequest req) {
        return policies.update(id, req.weights(), req.note(), Auth.user().email());
    }

    @PostMapping("/{id}/submit")
    public Object submit(@PathVariable long id) {
        return policies.submit(id, Auth.user().email());
    }

    public record SimulateRequest(@NotBlank String txId) {}

    @PostMapping("/{id}/simulate")
    public Object simulate(@PathVariable long id, @Valid @RequestBody SimulateRequest req) {
        return simulation.simulate(id, req.txId(), Auth.user());
    }
}
