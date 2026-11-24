package com.contextshield.core.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Vendedor do marketplace. Tempo de atividade e reclamações viram sinais. */
@Entity
@Table(name = "seller")
@Getter
@Setter
@NoArgsConstructor
public class Seller {
    @Id
    private String id;
    private String name;
    private String initials;
    private String color;
    private String tagline;
    private String about;
    private LocalDate since;
    private String city;
    private String cnpj;
    private int sales;
    private BigDecimal rating;
    private Integer reviewCount;
    private String tier;
    private BigDecimal claimsRate;
}
