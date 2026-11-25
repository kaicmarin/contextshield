package com.contextshield.threat.web;

import com.contextshield.threat.IndicatorService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/internal/v1/indicators")
public class IndicatorController {
    private final IndicatorService indicators;

    public IndicatorController(IndicatorService indicators) {
        this.indicators = indicators;
    }

    @PostMapping
    public Map<String, Object> open(HttpServletRequest request, @RequestBody Map<String, String> body) {
        scope(request, "review");
        return indicators.open(org(request), actor(request), body.get("kind"), body.get("value"));
    }

    @PostMapping("/{id}/review")
    public Map<String, Object> review(HttpServletRequest request, @PathVariable String id, @RequestBody Map<String, String> body) {
        scope(request, "review");
        return indicators.review(org(request), actor(request), id, body.get("status"), body.get("note"), body.get("visibility"));
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request) {
        scope(request, "read");
        return indicators.list(org(request));
    }

    @GetMapping("/{id:IND-[A-Za-z0-9]+}/history")
    public List<Map<String, Object>> history(HttpServletRequest request, @PathVariable String id) {
        scope(request, "read");
        return indicators.history(org(request), id);
    }

    @GetMapping("/shared")
    public List<Map<String, Object>> shared(HttpServletRequest request) {
        scope(request, "read");
        return indicators.shared(org(request));
    }

    @PostMapping("/match")
    public Map<String, Object> match(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        scope(request, "lookup");
        return indicators.match(org(request), text(body.get("kind")), text(body.get("value")), Boolean.TRUE.equals(body.get("sharedOnly")));
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
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "O revisor vem do token.");
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

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
