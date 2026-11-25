package com.contextshield.core.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Conta de acesso. Senha guardada com BCrypt; o papel define o que o JWT libera. */
@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
public class AppUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String email;
    private String passwordHash;
    private String name;
    @Enumerated(EnumType.STRING)
    private Role role;
    private String customerId;
    private Long organizationId;
    private boolean active = true;
    private long tokenVersion;
    private LocalDateTime createdAt;

    public void bumpTokenVersion() {
        tokenVersion++;
    }

    @PrePersist
    void organizationDefault() {
        if (organizationId == null) organizationId = 1L;
    }

    public enum Role { CUSTOMER, ANALYST, ADMIN }
}
