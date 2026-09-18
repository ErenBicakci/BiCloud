package com.bic.cloud.controlplane.repository;

import com.bic.cloud.controlplane.model.ProjectImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    boolean existsByProject_IdAndServiceName(Long projectId, String serviceName);

    @Query("""
        SELECT DISTINCT pi FROM ProjectImage pi
        JOIN FETCH pi.project p
        JOIN FETCH p.owner
        LEFT JOIN FETCH pi.environmentVariables
    """)
    List<ProjectImage> findAllWithProject();

    @Query("""
        SELECT pi FROM ProjectImage pi
        JOIN FETCH pi.project p
        JOIN FETCH p.owner
        LEFT JOIN FETCH pi.environmentVariables
        WHERE pi.id = :id
    """)
    Optional<ProjectImage> findByIdForDeployment(Long id);

    @Transactional
    @Modifying
    @Query("""
        UPDATE ProjectImage pi
        SET pi.consecutiveDeployFailures = pi.consecutiveDeployFailures + 1,
            pi.lastDeployFailureAt = :failedAt
        WHERE pi.id = :id
    """)
    int recordDeployFailure(@Param("id") Long id, @Param("failedAt") Instant failedAt);

    @Transactional
    @Modifying
    @Query("""
        UPDATE ProjectImage pi
        SET pi.consecutiveDeployFailures = 0,
            pi.lastDeployFailureAt = NULL
        WHERE pi.id = :id
          AND (pi.consecutiveDeployFailures > 0 OR pi.lastDeployFailureAt IS NOT NULL)
    """)
    int clearDeployFailures(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query("""
        UPDATE ProjectImage pi
        SET pi.consecutiveDeployFailures = CASE
                WHEN pi.consecutiveDeployFailures < :minFailures THEN :minFailures
                ELSE pi.consecutiveDeployFailures
            END,
            pi.lastDeployFailureAt = :failedAt
        WHERE pi.id = :id
    """)
    int markCrashLoop(@Param("id") Long id,
                      @Param("minFailures") int minFailures,
                      @Param("failedAt") Instant failedAt);

    @Transactional
    @Modifying
    @Query("""
        UPDATE ProjectImage pi
        SET pi.desiredReplicas = :newReplicas,
            pi.lastAutoscaledAt = :scaledAt
        WHERE pi.id = :id
          AND pi.desiredReplicas = :expectedReplicas
          AND pi.autoscalingEnabled = true
          AND pi.stoppedByUser = false
    """)
    int applyAutoscaledReplicas(@Param("id") Long id,
                                @Param("expectedReplicas") int expectedReplicas,
                                @Param("newReplicas") int newReplicas,
                                @Param("scaledAt") Instant scaledAt);
}
