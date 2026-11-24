package com.contextshield.core.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Aparelho visto pelo SDK de dispositivo. trusted = já marcado como confiável pelo cliente. */
@Entity
@Table(name = "device")
@Getter
@Setter
@NoArgsConstructor
public class Device {
    @Id
    private String id;
    private String customerId;
    private String name;
    private String detail;
    private String kind;
    private String location;
    private LocalDateTime firstSeen;
    private LocalDateTime lastSeen;
    private boolean trusted;
}
