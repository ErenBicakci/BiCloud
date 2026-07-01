package com.bic.cloud.controlplane.repository;

import com.bic.cloud.controlplane.model.WorkerState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface WorkerStateRepository extends JpaRepository<WorkerState, UUID> {

    List<WorkerState> findByStatusAndLastHeartbeatBefore(
            WorkerState.NodeStatus status, Instant threshold);

}
