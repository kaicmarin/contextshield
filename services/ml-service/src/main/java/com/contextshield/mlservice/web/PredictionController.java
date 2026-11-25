package com.contextshield.mlservice.web;

import com.contextshield.mlservice.CatalogService;
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
@RequestMapping("/internal/v1")
public class PredictionController {
    private final CatalogService catalog;

    public PredictionController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/models")
    public Map<String, Object> models(HttpServletRequest request) {
        scope(request, "read");
        return catalog.models(org(request));
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(HttpServletRequest request) {
        scope(request, "read");
        return catalog.overview(org(request));
    }

    @PostMapping("/predictions")
    public Map<String, Object> predict(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        scope(request, "predict");
        return catalog.predict(org(request), body);
    }

    @PostMapping("/training")
    public Map<String, Object> training(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        scope(request, "train");
        return catalog.enqueue(org(request), actor(request), body);
    }

    @PostMapping("/experiments")
    public Map<String, Object> experiments(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        scope(request, "train");
        return catalog.enqueue(org(request), actor(request), body);
    }

    @GetMapping("/experiments/{id}")
    public Map<String, Object> experiment(HttpServletRequest request, @PathVariable long id) {
        scope(request, "read");
        return catalog.experiment(org(request), id);
    }

    @GetMapping("/monitor")
    public Map<String, Object> monitor(HttpServletRequest request) {
        scope(request, "read");
        return catalog.monitor(org(request));
    }

    @GetMapping("/shadow")
    public List<Map<String, Object>> shadow(HttpServletRequest request, @RequestParam String txId) {
        scope(request, "read");
        return catalog.shadow(org(request), txId);
    }

    @PostMapping("/models/{id}/approve")
    public Map<String, Object> approve(HttpServletRequest request, @PathVariable long id) {
        scope(request, "catalog");
        return catalog.approve(org(request), id, actor(request));
    }

    @PostMapping("/models/{id}/reject")
    public Map<String, Object> reject(HttpServletRequest request, @PathVariable long id) {
        scope(request, "catalog");
        return catalog.reject(org(request), id);
    }

    @GetMapping("/models/compare")
    public Map<String, Object> compare(HttpServletRequest request, @RequestParam long left, @RequestParam long right) {
        scope(request, "read");
        return catalog.compare(org(request), left, right);
    }

    private static long org(HttpServletRequest request) {
        if (!(request.getAttribute("orgId") instanceof Long id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A organização vem do token.");
        }
        return id;
    }

    private static String actor(HttpServletRequest request) {
        Object actor = request.getAttribute("actor");
        if (!(actor instanceof String value) || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "O responsável vem do token.");
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
