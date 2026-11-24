package com.contextshield.core.service;

import com.contextshield.core.domain.AuditEntry;
import com.contextshield.core.repo.AuditEntryRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Trilha de auditoria: quem fez o quê, quando e em qual registro. */
@Service
public class AuditService {
    private final AuditEntryRepository repo;

    public AuditService(AuditEntryRepository repo) {
        this.repo = repo;
    }

    public void log(String actor, String action, String target, String detail) {
        log(actor, action, target, detail, LocalDateTime.now());
    }

    public void log(String actor, String action, String target, String detail, LocalDateTime at) {
        AuditEntry e = new AuditEntry();
        e.setAt(at);
        e.setActor(actor);
        e.setAction(action);
        e.setTarget(target);
        e.setDetail(detail);
        repo.save(e);
    }
}
