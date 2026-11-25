package com.contextshield.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Um relato de golpe abre um caso na fila de investigação.
 * Se o serviço de casos não responder, o relato continua gravado.
 */
@Service
public class CaseIntake {
    private static final Logger log = LoggerFactory.getLogger(CaseIntake.class);
    private final InternalPlatformClient platform;

    public CaseIntake(InternalPlatformClient platform) {
        this.platform = platform;
    }

    public void fromTransaction(long organizationId, String txId, String actor, String reason) {
        open(organizationId, actor, "transaction", txId, txId, reason);
    }

    public void fromIncident(long organizationId, String incidentId, String txId, String actor, String description) {
        open(organizationId, actor, "incident", incidentId, txId, description);
    }

    private void open(long organizationId, String actor, String sourceType, String sourceId, String txId, String reason) {
        if (!platform.investigationEnabled() || actor == null || actor.isBlank() || "core-api".equals(actor)) return;
        try {
            if (exists(organizationId, actor, sourceType, sourceId)) return;
            Object created = platform.investigation("POST", "/internal/v1/cases", organizationId, actor, "investigate",
                    Map.of("sourceType", sourceType, "sourceId", sourceId, "txId", txId == null ? "" : txId, "priority", "NORMAL"));
            if (!(created instanceof Map<?, ?> map) || map.get("id") == null || reason == null || reason.isBlank()) return;
            String note = reason.trim();
            if (note.length() > 2000) note = note.substring(0, 2000);
            platform.investigation("POST", "/internal/v1/cases/" + map.get("id") + "/evidence", organizationId, actor, "investigate",
                    Map.of("kind", "NOTE", "body", note));
        } catch (RuntimeException e) {
            log.warn("O relato {} {} ficou gravado, mas o caso não abriu: {}", sourceType, sourceId, e.getMessage());
        }
    }

    private boolean exists(long organizationId, String actor, String sourceType, String sourceId) {
        Object body = platform.investigation("GET", "/internal/v1/cases", organizationId, actor, "read", null);
        if (!(body instanceof List<?> rows)) return false;
        for (Object row : rows) {
            if (row instanceof Map<?, ?> map && sourceType.equals(String.valueOf(map.get("sourceType"))) && sourceId.equals(String.valueOf(map.get("sourceId")))) {
                return true;
            }
        }
        return false;
    }
}
