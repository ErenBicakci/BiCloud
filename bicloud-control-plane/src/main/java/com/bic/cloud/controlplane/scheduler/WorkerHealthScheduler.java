package com.bic.cloud.controlplane.scheduler;

import com.bic.cloud.controlplane.model.AuditEvent;
import com.bic.cloud.controlplane.model.ContainerInstance;
import com.bic.cloud.controlplane.model.WorkerState;
import com.bic.cloud.controlplane.repository.ContainerInstanceRepository;
import com.bic.cloud.controlplane.repository.WorkerStateRepository;
import com.bic.cloud.controlplane.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerHealthScheduler {

    private final WorkerStateRepository stateRepository;
    private final ContainerInstanceRepository containerInstanceRepository;
    private final AuditService auditService;

    private static final long HEARTBEAT_TIMEOUT_SECONDS = 30;
    private static final String COMPONENT = "health-monitor";

    @Scheduled(fixedRate = 20000)
    @Transactional
    public void detectOfflineWorkers() {

        Instant threshold = Instant.now().minusSeconds(HEARTBEAT_TIMEOUT_SECONDS);

        List<WorkerState> staleActiveWorkers = stateRepository
                .findByStatusAndLastHeartbeatBefore(WorkerState.NodeStatus.ACTIVE, threshold);

        List<WorkerState> staleOverloadedWorkers = stateRepository
                .findByStatusAndLastHeartbeatBefore(WorkerState.NodeStatus.OVERLOADED, threshold);

        markOffline(staleActiveWorkers);
        markOffline(staleOverloadedWorkers);
    }

    private void markOffline(List<WorkerState> workers) {
        for (WorkerState state : workers) {
            state.setStatus(WorkerState.NodeStatus.OFFLINE);
            stateRepository.save(state);

            log.warn("Worker {} marked as OFFLINE (last heartbeat: {})",
                    state.getWorker().getWorkerName(),
                    state.getLastHeartbeat());

            auditService.workerAction(COMPONENT, AuditEvent.AuditAction.WORKER_OFFLINE,
                    AuditEvent.Severity.WARN, state.getWorker().getWorkerName(),
                    "Worker marked OFFLINE - no heartbeat for " + HEARTBEAT_TIMEOUT_SECONDS
                            + "s; its containers are being marked FAILED");

            markWorkerContainersFailed(state.getWorker().getId(), state.getWorker().getWorkerName());
        }
    }

    private void markWorkerContainersFailed(UUID workerNodeId, String workerName) {
        List<ContainerInstance> orphans =
                containerInstanceRepository.findRunningByWorkerNodeId(workerNodeId);

        if (orphans.isEmpty()) {
            return;
        }

        log.warn("Marking {} orphaned container(s) as FAILED for OFFLINE worker '{}'",
                orphans.size(), workerName);

        for (ContainerInstance ci : orphans) {
            ci.setStatus(ContainerInstance.InstanceStatus.FAILED);
            containerInstanceRepository.save(ci);

            log.warn("  -> Orphan container FAILED: instanceId={}, dockerId={}, service={}",
                    ci.getId(),
                    ci.getDockerContainerId(),
                    ci.getProjectImage().getServiceName());
        }
    }
}
