package com.contextshield.core.web;

import com.contextshield.core.config.CurrentUser;
import com.contextshield.core.service.InternalPlatformClient;
import com.contextshield.core.service.OrganizationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Analistas entram pela core-api. A organização é a da sessão. */
@RestController
@RequestMapping("/api/ops")
public class InternalWorkController {
    private final InternalPlatformClient platform;
    private final OrganizationService organizations;

    public InternalWorkController(InternalPlatformClient platform, OrganizationService organizations) {
        this.platform = platform;
        this.organizations = organizations;
    }

    @GetMapping("/indicators")
    public Object indicators(CurrentUser user) {
        return platform.threat("GET", "/internal/v1/indicators", organizations.idOf(user), null);
    }

    @PostMapping("/indicators")
    public Object openIndicator(CurrentUser user, @RequestBody Map<String, String> body) {
        return platform.threat("POST", "/internal/v1/indicators", organizations.idOf(user),
                Map.of("kind", body.getOrDefault("kind", ""), "value", body.getOrDefault("value", "")));
    }

    @PostMapping("/indicators/{id}/review")
    public Object reviewIndicator(CurrentUser user, @PathVariable String id, @RequestBody Map<String, String> body) {
        return platform.threat("POST", "/internal/v1/indicators/" + ref(id) + "/review", organizations.idOf(user), body);
    }

    @GetMapping("/investigations")
    public Object investigations(CurrentUser user) {
        return platform.investigation("GET", "/internal/v1/cases", organizations.idOf(user), null);
    }

    @PostMapping("/investigations")
    public Object openInvestigation(CurrentUser user, @RequestBody Map<String, String> body) {
        return platform.investigation("POST", "/internal/v1/cases", organizations.idOf(user),
                Map.of("sourceType", body.getOrDefault("sourceType", "transaction"),
                        "sourceId", body.getOrDefault("sourceId", ""),
                        "actor", user.email()));
    }

    @PostMapping("/investigations/{id}/transition")
    public Object moveInvestigation(CurrentUser user, @PathVariable String id, @RequestBody Map<String, String> body) {
        return platform.investigation("POST", "/internal/v1/cases/" + ref(id) + "/transition", organizations.idOf(user),
                Map.of("state", body.getOrDefault("state", ""),
                        "note", body.getOrDefault("note", ""),
                        "actor", user.email()));
    }

    private static String ref(String id) {
        if (id == null || !id.matches("[A-Za-z0-9-]{4,40}")) throw ApiException.badRequest("Identificador inválido.");
        return id;
    }
}
