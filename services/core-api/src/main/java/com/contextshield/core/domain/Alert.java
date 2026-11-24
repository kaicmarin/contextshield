package com.contextshield.core.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Aviso que o app do banco mostra ao cliente (compra com alerta, pausada, bloqueada...). */
@Entity
@Table(name = "alert")
@Getter
@Setter
@NoArgsConstructor
public class Alert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String customerId;
    private String txId;
    private String kind;
    private String title;
    private String body;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
