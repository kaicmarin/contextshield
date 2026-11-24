package com.contextshield.core.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Estabelecimento (visão do emissor). A NEXA é um marketplace com vários vendedores. */
@Entity
@Table(name = "merchant")
@Getter
@Setter
@NoArgsConstructor
public class Merchant {
    @Id
    private String id;
    private String name;
    private String legalName;
    private String domain;
    private String cnpj;
    private String city;
    private String category;
    private LocalDate since;
    private boolean verified;
    private boolean marketplace;
}
