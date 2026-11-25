package com.contextshield.investigation.web;

import com.contextshield.investigation.CaseService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/internal/v1/cases")
public class CaseController {
    private final CaseService cases;

    public CaseController(CaseService cases) {
        this.cases = cases;
    }

    @PostMapping
    public Map<String, Object> open(HttpServletRequest request, @RequestBody Map<String, String> body) {
        scope(request, "investigate");
        return cases.open(org(request), actor(request), body.get("sourceType"), body.get("sourceId"), body.get("txId"), body.get("priority"));
    }

    @PostMapping("/{id}/assign")
    public Map<String, Object> assign(HttpServletRequest request, @PathVariable String id) {
        scope(request, "investigate");
        return cases.assign(org(request), id, actor(request));
    }

    @PostMapping("/{id}/transition")
    public Map<String, Object> move(HttpServletRequest request, @PathVariable String id, @RequestBody Map<String, String> body) {
        scope(request, "investigate");
        return cases.move(org(request), id, body.get("state"), body.get("note"), actor(request));
    }

    @PostMapping("/{id}/evidence")
    public Map<String, Object> evidence(HttpServletRequest request, @PathVariable String id, @RequestBody Map<String, String> body) {
        scope(request, "investigate");
        return cases.evidence(org(request), id, actor(request), body.get("kind"), body.get("body"));
    }

    @PostMapping("/{id}/comments")
    public Map<String, Object> comment(HttpServletRequest request, @PathVariable String id, @RequestBody Map<String, String> body) {
        scope(request, "investigate");
        return cases.comment(org(request), id, actor(request), body.get("body"));
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request, @RequestParam(required = false) String state) {
        scope(request, "read");
        return cases.list(org(request), state);
    }

    @GetMapping("/report")
    public Map<String, Object> report(HttpServletRequest request) {
        scope(request, "report");
        return cases.report(org(request));
    }

    @GetMapping("/{id:CASE-[A-Za-z0-9]+}")
    public Map<String, Object> one(HttpServletRequest request, @PathVariable String id) {
        scope(request, "read");
        return cases.dossier(org(request), id);
    }

    private static long org(HttpServletRequest request) {
        if (!(request.getAttribute("orgId") instanceof Long id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A organização vem do token, não do corpo.");
        }
        return id;
    }

    private static String actor(HttpServletRequest request) {
        Object actor = request.getAttribute("actor");
        if (!(actor instanceof String value) || value.isBlank() || "core-api".equals(value)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "O analista vem do token.");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static void scope(HttpServletRequest request, String needed) {
        Object raw = request.getAttribute("scopes");
        if (!(raw instanceof Set<?> scopes) || !scopes.contains(needed)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Operação não autorizada.");
        }
    }
}
