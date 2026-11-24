package com.contextshield.core.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;


@Entity
@Table(name = "status_change")
@Getter
@Setter
@NoArgsConstructor
public class StatusChange {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String txId;
    private String fromStatus;
    private String toStatus;
    private String reason;
    private String actor;
    private LocalDateTime at;
}
