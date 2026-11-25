package com.contextshield.core.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Compra com cartão. O status segue a máquina de estados em TransactionStatus. */
@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
public class Transaction {
    @Id
    private String id;
    private String customerId;
    private String merchantId;
    private String deviceId;
    private String addressId;
    private String cardId;
    private BigDecimal amount;
    private String description;
    private String declaredOrigin;
    private String coachScript;
    private String scenario;
    @Enumerated(EnumType.STRING)
    private TransactionStatus status;
    private String unavailable;
    private Long organizationId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Integer version;

}
