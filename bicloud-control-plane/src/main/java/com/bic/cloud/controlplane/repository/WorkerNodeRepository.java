package com.bic.cloud.controlplane.repository;

import com.bic.cloud.controlplane.model.WorkerNode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkerNodeRepository extends JpaRepository<WorkerNode, UUID> {


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WorkerNode w WHERE w.id = :id")
    Optional<WorkerNode> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WorkerNode w WHERE w.workerName = :name")
    Optional<WorkerNode> findByWorkerNameForUpdate(@Param("name") String name);
}
