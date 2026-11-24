package com.contextshield.core.repo;

import com.contextshield.core.domain.AssessmentRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssessmentRecordRepository extends JpaRepository<AssessmentRecord, Long> {
    List<AssessmentRecord> findByTxIdOrderByIdAsc(String txId);

    Optional<AssessmentRecord> findFirstByTxIdOrderByIdDesc(String txId);
}
