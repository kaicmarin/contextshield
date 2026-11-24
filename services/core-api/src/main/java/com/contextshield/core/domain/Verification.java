package com.contextshield.core.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Respostas do cliente na tela de verificação (JSON). */
@Entity
@Table(name = "verification")
@Getter
@Setter
@NoArgsConstructor
public class Verification {
    @Id
    private String txId;
    private String answers;
    private LocalDateTime answeredAt;
}
