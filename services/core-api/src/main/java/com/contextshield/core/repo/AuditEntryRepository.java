package com.contextshield.core.repo;

import com.contextshield.core.domain.AuditEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AuditEntryRepository extends JpaRepository<AuditEntry, Long> {

    Optional<AuditEntry> findFirstByTargetAndActionOrderByIdDesc(String target, String action);

    @Query("""
            select a from AuditEntry a
            where (:action is null or a.action = :action)
              and (:q is null or lower(coalesce(a.actor, '')) like :q
                   or lower(coalesce(a.action, '')) like :q
                   or lower(coalesce(a.target, '')) like :q
                   or lower(coalesce(a.detail, '')) like :q)
            """)
    Page<AuditEntry> search(@Param("action") String action, @Param("q") String q, Pageable pageable);

    @Query("select distinct a.action from AuditEntry a order by a.action")
    List<String> actions();
}
