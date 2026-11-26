package com.contextshield.mlservice.web;

import com.contextshield.mlservice.ActivityService;
import com.contextshield.mlservice.CatalogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/internal/v1")
public class PredictionController {
    private final CatalogService catalog;
    private final ActivityService activity;

    public PredictionController(CatalogService catalog, ActivityService activity) {
        this.catalog = catalog;
        this.activity = activity;
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

    @GetMapping("/activity/summary")
    public Map<String, Object> activitySummary(HttpServletRequest request, @RequestParam(defaultValue = "7") int days) {
        scope(request, "read");
        return activity.summary(org(request), days);
    }

    @GetMapping("/activity/events")
    public List<Map<String, Object>> activityEvents(HttpServletRequest request,
                                                   @RequestParam(defaultValue = "200") int limit,
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
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        scope(request, "read");
        return activity.eventsForMerge(org(request), limit, cursor, modelId, algorithm, eventType,
                prediction, align, stage, status, q, from, to);
    }

    /** Mantido para chamadas diretas ao ML; a Core API prefere /activity/events. */
    @GetMapping("/activity")
    public Map<String, Object> activity(HttpServletRequest request,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "25") int size,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(required = false) Long modelId,
                                         @RequestParam(required = false) String algorithm,
                                         @RequestParam(required = false) String prediction,
                                         @RequestParam(required = false) String align,
                                         @RequestParam(required = false) String stage,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String q) {
        scope(request, "read");
        return activity.feed(org(request), page, size, cursor, modelId, algorithm, prediction, align, stage, status, q);
    }

    @GetMapping("/models/{id}/activity")
    public Map<String, Object> modelActivity(HttpServletRequest request, @PathVariable long id) {
        scope(request, "read");
        return activity.modelStats(org(request), id);
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
