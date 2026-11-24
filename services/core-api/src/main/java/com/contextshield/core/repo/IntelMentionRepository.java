package com.contextshield.core.repo;

import com.contextshield.core.domain.IntelMention;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IntelMentionRepository extends JpaRepository<IntelMention, Long> {
    List<IntelMention> findByEntityId(String entityId);

    List<IntelMention> findByIncidentId(String incidentId);

    boolean existsByEntityIdAndTxId(String entityId, String txId);
}
