package com.contextshield.core.repo;

import com.contextshield.core.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, String> {
    List<Transaction> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    List<Transaction> findTop50ByCustomerIdOrderByCreatedAtDesc(String customerId);

    List<Transaction> findAllByOrderByCreatedAtDesc();

    List<Transaction> findTop300ByOrderByCreatedAtDesc();

    List<Transaction> findTop300ByStatusOrderByCreatedAtDesc(com.contextshield.core.domain.TransactionStatus status);

    List<Transaction> findByStatusOrderByCreatedAtDesc(com.contextshield.core.domain.TransactionStatus status);

    boolean existsByCustomerIdAndAddressIdAndIdNotAndCreatedAtBeforeAndStatusIn(
            String customerId, String addressId, String id, java.time.LocalDateTime before,
            java.util.Collection<com.contextshield.core.domain.TransactionStatus> statuses);

    List<Transaction> findByAddressId(String addressId);
}
