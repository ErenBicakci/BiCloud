package com.bic.cloud.controlplane.repository;

import com.bic.cloud.controlplane.model.BicloudUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BicloudUserRepository extends JpaRepository<BicloudUser, UUID> {

    Optional<BicloudUser> findByUsername(String username);

    boolean existsByUsername(String username);

    long countByRole(String role);
}
