package com.contextshield.core.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Resposta registrada por um analista em um relato. O autor sai da conta autenticada. */
@Entity
@Table(name = "incident_reply")
@Getter
@Setter
@NoArgsConstructor
public class IncidentReply {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String incidentId;
    private String body;
    private String authorEmail;
    private String authorName;
    private String serviceStatus;
    private LocalDateTime createdAt;
}
