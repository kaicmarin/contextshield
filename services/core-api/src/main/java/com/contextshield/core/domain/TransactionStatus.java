package com.contextshield.core.domain;

import java.util.List;
import java.util.Map;

/**
 * Máquina de estados da compra. Estados finais (BLOCKED, CANCELLED, INCIDENT_RECORDED) não reabrem.
 */
public enum TransactionStatus {
    RECEIVED, ANALYZING, APPROVED, APPROVED_WITH_ALERT, CONTEXT_REQUIRED, INTERVENTION, BLOCKED, CANCELLED, INCIDENT_RECORDED;

    private static final Map<TransactionStatus, List<TransactionStatus>> NEXT = Map.of(
            RECEIVED, List.of(ANALYZING, CANCELLED),
            ANALYZING, List.of(APPROVED, APPROVED_WITH_ALERT, CONTEXT_REQUIRED, INTERVENTION, BLOCKED),
            CONTEXT_REQUIRED, List.of(APPROVED, APPROVED_WITH_ALERT, INTERVENTION, BLOCKED, CANCELLED),
            INTERVENTION, List.of(APPROVED_WITH_ALERT, CANCELLED, BLOCKED),
            APPROVED, List.of(INCIDENT_RECORDED),
            APPROVED_WITH_ALERT, List.of(INCIDENT_RECORDED),
            BLOCKED, List.of(),
            CANCELLED, List.of(),
            INCIDENT_RECORDED, List.of());

    public List<TransactionStatus> next() {
        return NEXT.get(this);
    }

    public boolean canGoTo(TransactionStatus to) {
        return NEXT.get(this).contains(to);
    }

    public boolean isTerminal() {
        return NEXT.get(this).isEmpty();
    }

    public boolean isAuthorized() {
        return this == APPROVED || this == APPROVED_WITH_ALERT || this == INCIDENT_RECORDED;
    }

    public static TransactionStatus fromDecision(String decision) {
        return switch (decision) {
            case "APPROVE" -> APPROVED;
            case "APPROVE_WITH_ALERT" -> APPROVED_WITH_ALERT;
            case "REQUEST_CONTEXT" -> CONTEXT_REQUIRED;
            case "INTERVENE" -> INTERVENTION;
            case "BLOCK" -> BLOCKED;
            default -> throw new IllegalArgumentException("Decisão desconhecida: " + decision);
        };
    }
}
