package com.contextshield.core.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Algo citado em relatos: telefone, domínio, perfil, destino de entrega ou vendedor. */
@Entity
@Table(name = "intel_entity")
@Getter
@Setter
@NoArgsConstructor
public class IntelEntity {
    @Id
    private String id;
    private String kind;
    @Column(name = "display_value")
    private String value;
    private String sub;
    private LocalDateTime firstSeen;
    private LocalDateTime lastSeen;
    private String status;
    private BigDecimal confidence;
    private LocalDateTime validUntil;
    private String evidenceSource;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
}
