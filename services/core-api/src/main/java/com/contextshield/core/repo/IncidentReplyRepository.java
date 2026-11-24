package com.contextshield.core.repo;

import com.contextshield.core.domain.IncidentReply;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IncidentReplyRepository extends JpaRepository<IncidentReply, Long> {
    List<IncidentReply> findByIncidentIdOrderByIdAsc(String incidentId);
}
