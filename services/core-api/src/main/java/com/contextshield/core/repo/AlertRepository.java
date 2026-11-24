package com.contextshield.core.repo;

import com.contextshield.core.domain.Alert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlertRepository extends JpaRepository<Alert, Long> {
    List<Alert> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    long countByCustomerIdAndReadAtIsNull(String customerId);
}
