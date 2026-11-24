package com.contextshield.core.repo;

import com.contextshield.core.domain.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MerchantRepository extends JpaRepository<Merchant, String> {

}
