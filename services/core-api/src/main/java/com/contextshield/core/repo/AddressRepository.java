package com.contextshield.core.repo;

import com.contextshield.core.domain.Address;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AddressRepository extends JpaRepository<Address, String> {
    List<Address> findByKind(String kind);

    @org.springframework.data.jpa.repository.Query("select a from Address a join a.customerIds c where c = :customerId")
    List<Address> findOwnedBy(String customerId);
}
