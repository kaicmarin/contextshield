package com.contextshield.core.repo;

import com.contextshield.core.domain.Verification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VerificationRepository extends JpaRepository<Verification, String> {

}
