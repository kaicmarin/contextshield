package com.contextshield.core.repo;

import com.contextshield.core.domain.Incident;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IncidentRepository extends JpaRepository<Incident, String> {
    List<Incident> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    List<Incident> findAllByOrderByCreatedAtDesc();

    List<Incident> findByTxId(String txId);
}
