package com.bic.cloud.controlplane.repository;

import com.bic.cloud.controlplane.model.UserProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserProjectRepository extends JpaRepository<UserProject, Long> {

    Optional<UserProject> findById(Long id);

    /**
     * Project names are globally unique (not per owner): the Docker network,
     * gateway route key, mesh path and discovery all derive from the name, so
     * two owners sharing a name would share an isolation boundary.
     */
    boolean existsByName(String name);

    List<UserProject> findAllByOwner_Id(UUID ownerId);

    /** Used by the user-deletion guard: number of projects the user owns. */
    long countByOwner_Id(UUID ownerId);
}

