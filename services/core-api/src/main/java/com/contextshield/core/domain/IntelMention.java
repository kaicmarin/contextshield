package com.contextshield.core.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;


@Entity
@Table(name = "intel_mention")
@Getter
@Setter
@NoArgsConstructor
public class IntelMention {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String entityId;
    private String incidentId;
    private String txId;
    private String customerId;
    private String reason;
    private LocalDateTime at;
}
