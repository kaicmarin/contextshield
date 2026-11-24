package com.contextshield.core.repo;

import com.contextshield.core.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, String> {
    List<Product> findByCategorySlug(String slug);

    List<Product> findBySellerId(String sellerId);

    List<Product> findByNameContainingIgnoreCase(String q);
}
