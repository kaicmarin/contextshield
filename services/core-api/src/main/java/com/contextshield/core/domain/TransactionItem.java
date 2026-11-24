package com.contextshield.core.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;


@Entity
@Table(name = "transaction_item")
@Getter
@Setter
@NoArgsConstructor
public class TransactionItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String txId;
    private String productId;
    private String sellerId;
    private String name;
    private int quantity;
    private BigDecimal unitPrice;
}
