package com.bic.cloud.controlplane.repository;

import com.bic.cloud.controlplane.model.ContainerInstance;
import com.bic.cloud.controlplane.model.ProjectImage;
import com.bic.cloud.controlplane.model.WorkerNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContainerInstanceRepository extends JpaRepository<ContainerInstance, UUID> {

    List<ContainerInstance> findByWorkerNode(WorkerNode workerNode);

    @EntityGraph(attributePaths = {"workerNode", "projectImage", "projectImage.project"})
    List<ContainerInstance> findAllByStatus(ContainerInstance.InstanceStatus status);

    Optional<ContainerInstance> findByDockerContainerId(String dockerContainerId);

    long countByWorkerNodeAndStatus(WorkerNode workerNode, ContainerInstance.InstanceStatus status);

    @EntityGraph(attributePaths = {"workerNode", "projectImage", "projectImage.project"})
    List<ContainerInstance> findByProjectImage(ProjectImage image);

    /**
     * Eagerly loads worker + image + project: callers iterate these instances
     * outside a transaction (gateway deregister, worker stop calls), so lazy
     * access would fail there.
     */
    @EntityGraph(attributePaths = {"workerNode", "projectImage", "projectImage.project"})
    List<ContainerInstance> findByProjectImageAndStatus(ProjectImage image, ContainerInstance.InstanceStatus status);

    long countByProjectImageAndStatus(ProjectImage image, ContainerInstance.InstanceStatus status);

    /** Crash-loop detection: count of instances created after the given moment that ended FAILED. */
    long countByProjectImageAndStatusAndCreatedAtAfter(
            ProjectImage image, ContainerInstance.InstanceStatus status, java.time.Instant after);

    @Query("""
        SELECT ci FROM ContainerInstance ci
        JOIN FETCH ci.workerNode
        JOIN FETCH ci.projectImage pi
        JOIN FETCH pi.project p
        WHERE p.name = :projectName
          AND pi.serviceName = :serviceName
          AND ci.status = 'RUNNING'
    """)
    List<ContainerInstance> findRunningByProjectNameAndServiceName(
            @Param("projectName") String projectName,
            @Param("serviceName") String serviceName);

    @Query("""
        SELECT ci FROM ContainerInstance ci
        JOIN FETCH ci.workerNode
        JOIN FETCH ci.projectImage pi
        JOIN FETCH pi.project p
        WHERE p.id = :projectId
    """)
    List<ContainerInstance> findAllByProjectId(@Param("projectId") Long projectId);

    @Query("""
        SELECT ci FROM ContainerInstance ci
        JOIN FETCH ci.workerNode
        JOIN FETCH ci.projectImage pi
        JOIN FETCH pi.project p
        WHERE p.id = :projectId
          AND ci.status = 'RUNNING'
    """)
    List<ContainerInstance> findRunningByProjectId(@Param("projectId") Long projectId);

    @Query("""
        SELECT ci FROM ContainerInstance ci
        WHERE ci.workerNode.id = :workerNodeId
          AND ci.status = 'RUNNING'
    """)
    List<ContainerInstance> findRunningByWorkerNodeId(@Param("workerNodeId") UUID workerNodeId);

    @EntityGraph(attributePaths = {"workerNode", "projectImage", "projectImage.project"})
    List<ContainerInstance> findByWorkerNode_IdAndStatus(UUID workerNodeId, ContainerInstance.InstanceStatus status);


    @Query("""
        SELECT ci FROM ContainerInstance ci
        JOIN FETCH ci.workerNode
        JOIN FETCH ci.projectImage pi
        JOIN FETCH pi.project p
        WHERE p.name = :projectName
          AND ci.status = 'RUNNING'
    """)
    List<ContainerInstance> findRunningByProjectName(@Param("projectName") String projectName);


    @EntityGraph(attributePaths = {"workerNode", "projectImage", "projectImage.project"})
    @Query("""
        SELECT ci FROM ContainerInstance ci
        WHERE ci.projectImage.project.id = :projectId
          AND (:serviceName IS NULL OR ci.projectImage.serviceName = :serviceName)
          AND (:hasStatusFilter = false OR ci.status IN :statuses)
          AND (:search IS NULL OR :search = ''
               OR LOWER(COALESCE(ci.dockerContainerId, '')) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(COALESCE(ci.workerNode.workerName, '')) LIKE LOWER(CONCAT('%', :search, '%')))
    """)
    Page<ContainerInstance> searchByProject(
            @Param("projectId") Long projectId,
            @Param("serviceName") String serviceName,
            @Param("hasStatusFilter") boolean hasStatusFilter,
            @Param("statuses") List<ContainerInstance.InstanceStatus> statuses,
            @Param("search") String search,
            Pageable pageable);


    /**
     * Reserved resources per worker: summed memory and CPU limits of the
     * RUNNING/PENDING containers assigned to it.
     * The scheduler scores on this reservation instead of live OS usage -
     * so in back-to-back deploys each assignment immediately affects the next pick.
     * Row format: [workerNodeId(UUID), sumMemoryMb(Long), sumCpuCores(Double)]
     */
    @Query("""
        SELECT ci.workerNode.id,
               COALESCE(SUM(pi.memoryLimitMb), 0),
               COALESCE(SUM(pi.cpuLimit), 0)
        FROM ContainerInstance ci
        JOIN ci.projectImage pi
        WHERE ci.status IN ('RUNNING', 'PENDING')
        GROUP BY ci.workerNode.id
    """)
    List<Object[]> sumReservedResourcesByWorker();

    /**
     * Anti-affinity input: how many live (RUNNING/PENDING) replicas of the
     * given service each worker already hosts.
     * Row format: [workerNodeId(UUID), replicaCount(Long)]
     */
    @Query("""
        SELECT ci.workerNode.id, COUNT(ci)
        FROM ContainerInstance ci
        WHERE ci.projectImage.id = :imageId
          AND ci.status IN ('RUNNING', 'PENDING')
        GROUP BY ci.workerNode.id
    """)
    List<Object[]> countAliveReplicasPerWorker(@Param("imageId") Long imageId);

    @Query("""
        SELECT ci.status, COUNT(ci) FROM ContainerInstance ci
        WHERE ci.projectImage.project.id = :projectId
          AND (:serviceName IS NULL OR ci.projectImage.serviceName = :serviceName)
          AND (:search IS NULL OR :search = ''
               OR LOWER(COALESCE(ci.dockerContainerId, '')) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(COALESCE(ci.workerNode.workerName, '')) LIKE LOWER(CONCAT('%', :search, '%')))
        GROUP BY ci.status
    """)
    List<Object[]> countByStatusForProject(
            @Param("projectId") Long projectId,
            @Param("serviceName") String serviceName,
            @Param("search") String search);
}
