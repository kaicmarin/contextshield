package com.contextshield.core.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Cartão simulado do cliente (só para exibição; não há autorização real). */
@Entity
@Table(name = "card")
@Getter
@Setter
@NoArgsConstructor
public class Card {
    @Id
    private String id;
    private String customerId;
    private String last4;
    private String holder;
    private String product;
    private String kind;
    private String expiry;
    private BigDecimal creditLimit;
    private BigDecimal available;
    private String status;
}
