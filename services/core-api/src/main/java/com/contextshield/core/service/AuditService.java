package com.contextshield.core.service;

import com.contextshield.core.domain.AuditEntry;
import com.contextshield.core.repo.AuditEntryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** Trilha de auditoria: quem fez o quê, quando e em qual registro. */
@Service
public class AuditService {
    private final AuditEntryRepository repo;
    private final JdbcTemplate jdbc;

    public AuditService(AuditEntryRepository repo, JdbcTemplate jdbc) {
        this.repo = repo;
        this.jdbc = jdbc;
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
        e.setOrganizationId(organizationOf(actor));
        repo.save(e);
    }

    private long organizationOf(String actor) {
        if (actor == null || actor.isBlank()) return 1L;
        List<Long> ids = jdbc.query("select organization_id from app_user where lower(email) = lower(?)",
                (rs, n) -> rs.getObject(1) == null ? null : rs.getLong(1), actor);
        if (ids.isEmpty() || ids.get(0) == null || ids.get(0) == 0L) return 1L;
        return ids.get(0);
    }
}
