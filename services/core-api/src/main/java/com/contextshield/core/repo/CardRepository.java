package com.contextshield.core.repo;

import com.contextshield.core.domain.Card;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CardRepository extends JpaRepository<Card, String> {
    List<Card> findByCustomerId(String customerId);
}
