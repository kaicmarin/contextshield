package com.contextshield.core.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Avaliação devolvida pelo risk-engine. O JSON completo fica em payload. */
@Entity
@Table(name = "assessment")
@Getter
@Setter
@NoArgsConstructor
public class AssessmentRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String txId;
    private String stage;
    private int score;
    private String band;
    private String level;
    private int confidence;
    private String proposedDecision;
    private String decision;
    private String ruleId;
    private String ruleText;
    private String fragility;
    private Double durationMs;
    private String engine;
    private String payload;
    private LocalDateTime createdAt;
}
