package com.bic.cloud.controlplane.repository;

import com.bic.cloud.controlplane.model.ProjectImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectImageRepository extends JpaRepository<ProjectImage, Long> {
    Optional<ProjectImage> findById(Long id);

    @Query("""
        SELECT pi
        FROM ProjectImage pi
        JOIN FETCH pi.project
        WHERE pi.id = :id
    """)
    Optional<ProjectImage> findByIdWithProject(Long id);

    List<ProjectImage> findByProject_Id(Long projectId);

    /** Route key is projectName:serviceName -> a service name must be unique within its project. */
    boolean existsByProject_IdAndServiceName(Long projectId, String serviceName);

    /**
     * Fully initialized load for callers running OUTSIDE a transaction /
     * request session (self-healing, async deploys): project, owner and the
     * env-var map are all fetch-joined so no lazy access happens later.
     */
    @Query("""
        SELECT DISTINCT pi FROM ProjectImage pi
        JOIN FETCH pi.project p
        JOIN FETCH p.owner
        LEFT JOIN FETCH pi.environmentVariables
    """)
    List<ProjectImage> findAllWithProject();

    /** Single-image variant of {@link #findAllWithProject()}. */
    @Query("""
        SELECT pi FROM ProjectImage pi
        JOIN FETCH pi.project p
        JOIN FETCH p.owner
        LEFT JOIN FETCH pi.environmentVariables
        WHERE pi.id = :id
    """)
    Optional<ProjectImage> findByIdForDeployment(Long id);


}
