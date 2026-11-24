package com.contextshield.core.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Relato de golpe feito pelo cliente. Telefone, link e destino viram entidades de inteligência. */
@Entity
@Table(name = "incident")
@Getter
@Setter
@NoArgsConstructor
public class Incident {
    @Id
    private String id;
    private String customerId;
    private String txId;
    private String type;
    private String channel;
    private String description;
    private String contactHandle;
    private String contactLink;
    private String destinationText;
    private LocalDateTime occurredAt;
    private String status;
    private String closedAs;
    private String closeNote;
    private LocalDateTime createdAt;
    private LocalDateTime assignedAt;
    private LocalDateTime closedAt;
}
