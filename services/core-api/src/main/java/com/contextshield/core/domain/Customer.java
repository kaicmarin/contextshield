package com.contextshield.core.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Cliente do emissor (banco Aureon). Os dados de gasto alimentam o motor de risco. */
@Entity
@Table(name = "customer")
@Getter
@Setter
@NoArgsConstructor
public class Customer {
    @Id
    private String id;
    private String name;
    private String firstName;
    private String initials;
    private String email;
    private String phoneMask;
    private String city;
    private LocalDate since;
    private String segment;
    @Column(name = "avg_ticket_90d")
    private BigDecimal avgTicket90d;
    @Column(name = "purchases_90d")
    private int purchases90d;
}
