package com.contextshield.core.repo;

import com.contextshield.core.domain.StatusChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StatusChangeRepository extends JpaRepository<StatusChange, Long> {
    List<StatusChange> findByTxIdOrderByIdAsc(String txId);
}
