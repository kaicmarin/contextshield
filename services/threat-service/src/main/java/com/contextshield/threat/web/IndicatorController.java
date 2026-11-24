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

@RestController
@RequestMapping("/internal/v1/indicators")
public class IndicatorController {
    private final IndicatorService indicators;

    public IndicatorController(IndicatorService indicators) {
        this.indicators = indicators;
    }

    @PostMapping
    public Map<String, Object> open(HttpServletRequest request, @RequestBody Map<String, String> body) {
        return indicators.open(org(request), body.get("kind"), body.get("value"));
    }

    @PostMapping("/{id}/review")
    public Map<String, Object> review(HttpServletRequest request, @PathVariable String id, @RequestBody Map<String, String> body) {
        return indicators.review(org(request), id, body.get("status"), body.get("note"), body.get("visibility"));
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request) {
        return indicators.list(org(request));
    }

    @GetMapping("/shared")
    public List<Map<String, Object>> shared() {
        return indicators.shared();
    }

    private static long org(HttpServletRequest request) {
        if (!(request.getAttribute("orgId") instanceof Long id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A organização vem do token, não do corpo.");
        }
        return id;
    }
}
