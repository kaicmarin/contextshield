package com.contextshield.core.service;

import com.contextshield.core.domain.AuditEntry;
import com.contextshield.core.repo.AuditEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Trilha de auditoria: quem fez o quê, quando e em qual registro. */
@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private final AuditEntryRepository repo;
    private final JdbcTemplate jdbc;
    private final OrganizationService organizations;

    public AuditService(AuditEntryRepository repo, JdbcTemplate jdbc, OrganizationService organizations) {
        this.repo = repo;
        this.jdbc = jdbc;
        this.organizations = organizations;
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
        e.setOrganizationId(organizationOf(actor, target));
        repo.save(e);
    }

    private long organizationOf(String actor, String target) {
        Long fromTarget = organizationFromTarget(target);
        if (fromTarget != null) return fromTarget;
        Long fromActor = organizations.tryIdOfEmail(actor);
        if (fromActor != null) return fromActor;
        log.warn("Auditoria sem organização explícita: actor={} target={}", actor, target);
        return 1L;
    }

    private Long organizationFromTarget(String target) {
        if (target == null || target.isBlank()) return null;
        if (target.startsWith("TX-")) {
            try {
                return organizations.organizationOfTransaction(target);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        if (target.startsWith("CS-")) {
            return jdbc.query("select organization_id from incident where id = ?",
                    rs -> rs.next() ? (Long) rs.getObject(1) : null, target);
        }
        return null;
    }
}
