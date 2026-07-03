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

    /**
     * Containers of an OFFLINE worker are only marked FAILED once the silence
     * exceeds this second, longer window. A short network blip thus takes the
     * worker out of scheduling without triggering a redeploy storm: if the
     * heartbeat resumes in between, the worker returns to ACTIVE and its
     * containers were never touched (simplified Kubernetes pod eviction grace).
     */
    private static final long CONTAINER_FAIL_TIMEOUT_SECONDS = 60;

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

        // phase 2: workers that stayed OFFLINE past the longer window lose
        // their containers to self-healing. Cheap when repeated - the running
        // list is empty after the first pass.
        Instant failThreshold = Instant.now().minusSeconds(CONTAINER_FAIL_TIMEOUT_SECONDS);
        List<WorkerState> deadWorkers = stateRepository
                .findByStatusAndLastHeartbeatBefore(WorkerState.NodeStatus.OFFLINE, failThreshold);

        for (WorkerState state : deadWorkers) {
            markWorkerContainersFailed(state.getWorker().getId(), state.getWorker().getWorkerName());
        }
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
                            + "s; containers will be failed after " + CONTAINER_FAIL_TIMEOUT_SECONDS
                            + "s of silence");
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

        auditService.workerAction(COMPONENT, AuditEvent.AuditAction.WORKER_OFFLINE,
                AuditEvent.Severity.WARN, workerName,
                "Worker still silent after " + CONTAINER_FAIL_TIMEOUT_SECONDS
                        + "s - marking " + orphans.size() + " container(s) FAILED");

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
