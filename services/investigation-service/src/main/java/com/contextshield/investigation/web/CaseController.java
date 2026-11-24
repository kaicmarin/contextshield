package com.contextshield.investigation.web;

import com.contextshield.investigation.CaseService;
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
@RequestMapping("/internal/v1/cases")
public class CaseController {
    private final CaseService cases;

    public CaseController(CaseService cases) {
        this.cases = cases;
    }

    @PostMapping
    public Map<String, Object> open(HttpServletRequest request, @RequestBody Map<String, String> body) {
        return cases.open(org(request), body.get("sourceType"), body.get("sourceId"), body.get("actor"));
    }

    @PostMapping("/{id}/transition")
    public Map<String, Object> move(HttpServletRequest request, @PathVariable String id, @RequestBody Map<String, String> body) {
        return cases.move(org(request), id, body.get("state"), body.get("note"), body.get("actor"));
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request) {
        return cases.list(org(request));
    }

    private static long org(HttpServletRequest request) {
        if (!(request.getAttribute("orgId") instanceof Long id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A organização vem do token, não do corpo.");
        }
        return id;
    }
}
