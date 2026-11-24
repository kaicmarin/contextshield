package com.contextshield.core.repo;

import com.contextshield.core.domain.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, String> {
    List<Device> findByCustomerIdOrderByTrustedDescFirstSeenAsc(String customerId);
}
