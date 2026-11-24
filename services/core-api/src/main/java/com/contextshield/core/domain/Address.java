package com.contextshield.core.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Endereço de entrega. Os donos ficam em address_owner; ponto de retirada não tem dono. */
@Entity
@Table(name = "address")
@Getter
@Setter
@NoArgsConstructor
public class Address {
    @Id
    private String id;
    private String label;
    private String line1;
    private String line2;
    private String district;
    private String city;
    private String zip;
    private String kind;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "address_owner", joinColumns = @JoinColumn(name = "address_id"))
    @Column(name = "customer_id")
    private java.util.Set<String> customerIds = new java.util.HashSet<>();
}
