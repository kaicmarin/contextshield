package com.contextshield.core.web;

import com.contextshield.core.config.CurrentUser;
import com.contextshield.core.service.InternalPlatformClient;
import com.contextshield.core.service.OrganizationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Analistas entram pela core-api. A organização e o analista saem da sessão autenticada. */
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
    public Object indicators() {
        CurrentUser user = Auth.user();
        return platform.threat("GET", "/internal/v1/indicators", organizations.idOf(user), user.email(), "read", null);
    }

    @PostMapping("/indicators")
    public Object openIndicator(@RequestBody Map<String, String> body) {
        CurrentUser user = Auth.user();
        return platform.threat("POST", "/internal/v1/indicators", organizations.idOf(user), user.email(), "review,read",
                Map.of("kind", body.getOrDefault("kind", ""), "value", body.getOrDefault("value", "")));
    }

    @PostMapping("/indicators/{id}/review")
    public Object reviewIndicator(@PathVariable String id, @RequestBody Map<String, String> body) {
        CurrentUser user = Auth.user();
        return platform.threat("POST", "/internal/v1/indicators/" + ref(id) + "/review", organizations.idOf(user), user.email(), "review,read",
                Map.of("status", body.getOrDefault("status", ""), "note", body.getOrDefault("note", ""),
                        "visibility", body.getOrDefault("visibility", "PRIVATE")));
    }

    @GetMapping("/indicators/{id}/history")
    public Object indicatorHistory(@PathVariable String id) {
        CurrentUser user = Auth.user();
        return platform.threat("GET", "/internal/v1/indicators/" + ref(id) + "/history", organizations.idOf(user), user.email(), "read", null);
    }

    @GetMapping("/investigations")
    public Object investigations(@RequestParam(required = false) String state) {
        CurrentUser user = Auth.user();
        String path = state == null || state.isBlank() ? "/internal/v1/cases" : "/internal/v1/cases?state=" + ref(state);
        return platform.investigation("GET", path, organizations.idOf(user), user.email(), "read", null);
    }

    @GetMapping("/investigations/report")
    public Object investigationReport() {
        CurrentUser user = Auth.user();
        return platform.investigation("GET", "/internal/v1/cases/report", organizations.idOf(user), user.email(), "report,read", null);
    }

    @GetMapping("/investigations/{id}")
    public Object investigation(@PathVariable String id) {
        CurrentUser user = Auth.user();
        return platform.investigation("GET", "/internal/v1/cases/" + ref(id), organizations.idOf(user), user.email(), "read", null);
    }

    @PostMapping("/investigations")
    public Object openInvestigation(@RequestBody Map<String, String> body) {
        CurrentUser user = Auth.user();
        return platform.investigation("POST", "/internal/v1/cases", organizations.idOf(user), user.email(), "investigate,read",
                Map.of("sourceType", body.getOrDefault("sourceType", "transaction"),
                        "sourceId", body.getOrDefault("sourceId", ""),
                        "txId", body.getOrDefault("txId", body.getOrDefault("sourceId", "")),
                        "priority", body.getOrDefault("priority", "NORMAL")));
    }

    @PostMapping("/investigations/{id}/assign")
    public Object assignInvestigation(@PathVariable String id) {
        CurrentUser user = Auth.user();
        return platform.investigation("POST", "/internal/v1/cases/" + ref(id) + "/assign", organizations.idOf(user), user.email(), "investigate,read", Map.of());
    }

    @PostMapping("/investigations/{id}/transition")
    public Object moveInvestigation(@PathVariable String id, @RequestBody Map<String, String> body) {
        CurrentUser user = Auth.user();
        return platform.investigation("POST", "/internal/v1/cases/" + ref(id) + "/transition", organizations.idOf(user), user.email(), "investigate,read",
                Map.of("state", body.getOrDefault("state", ""), "note", body.getOrDefault("note", "")));
    }

    @PostMapping("/investigations/{id}/evidence")
    public Object evidence(@PathVariable String id, @RequestBody Map<String, String> body) {
        CurrentUser user = Auth.user();
        return platform.investigation("POST", "/internal/v1/cases/" + ref(id) + "/evidence", organizations.idOf(user), user.email(), "investigate,read",
                Map.of("kind", body.getOrDefault("kind", "NOTE"), "body", body.getOrDefault("body", "")));
    }

    @PostMapping("/investigations/{id}/comments")
    public Object comment(@PathVariable String id, @RequestBody Map<String, String> body) {
        CurrentUser user = Auth.user();
        return platform.investigation("POST", "/internal/v1/cases/" + ref(id) + "/comments", organizations.idOf(user), user.email(), "investigate,read",
                Map.of("body", body.getOrDefault("body", "")));
    }

    private static String ref(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{2,40}")) throw ApiException.badRequest("Identificador inválido.");
        return id;
    }
}
