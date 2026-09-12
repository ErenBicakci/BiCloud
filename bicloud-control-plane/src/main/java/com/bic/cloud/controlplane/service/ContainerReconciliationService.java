package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.client.WorkerHttpClient;
import com.bic.cloud.controlplane.exception.WorkerNotFoundException;
import com.bic.cloud.controlplane.model.AuditEvent;
import com.bic.cloud.controlplane.model.ContainerInstance;
import com.bic.cloud.controlplane.model.WorkerNode;
import com.bic.cloud.controlplane.repository.ContainerInstanceRepository;
import com.bic.cloud.controlplane.repository.WorkerNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContainerReconciliationService {

    private static final long NEW_INSTANCE_GRACE_SECONDS = 60;

    private final WorkerNodeRepository workerNodeRepository;
    private final ContainerInstanceRepository containerInstanceRepository;
    private final GatewayNotificationService gatewayNotificationService;
    private final WorkerHttpClient workerHttpClient;
    private final AuditService auditService;

    private static final String COMPONENT = "reconciler";

    public void reconcile(UUID workerId, List<String> actualRunningContainerIds) {

        WorkerNode worker = workerNodeRepository.findById(workerId)
                .orElseThrow(() -> new WorkerNotFoundException(workerId));

        Set<String> actualSet = actualRunningContainerIds == null
                ? new HashSet<>()
                : new HashSet<>(actualRunningContainerIds);

        recoverFalseFailed(worker, actualSet);
        reconcileStopping(worker, actualSet);
        cleanFalseStopped(worker, actualSet);

        List<ContainerInstance> dbRunning = containerInstanceRepository
                .findRunningByWorkerNodeId(workerId);

        Instant graceCutoff = Instant.now().minusSeconds(NEW_INSTANCE_GRACE_SECONDS);
        int zombiesFound = 0;

        for (ContainerInstance ci : dbRunning) {
            String dockerId = ci.getDockerContainerId();

            if (dockerId == null || dockerId.isBlank()) {
                continue;
            }

            Instant startedAt = ci.getStartedAt() != null ? ci.getStartedAt() : ci.getCreatedAt();
            if (startedAt != null && startedAt.isAfter(graceCutoff)) {
                continue;
            }

            if (!actualSet.contains(dockerId)) {
                ci.setStatus(ContainerInstance.InstanceStatus.FAILED);
                containerInstanceRepository.save(ci);
                zombiesFound++;

                log.warn("[Reconcile] Zombie container detected on worker '{}': instanceId={}, dockerId={}, service={} -> FAILED",
                        worker.getWorkerName(),
                        ci.getId(),
                        dockerId,
                        ci.getProjectImage().getServiceName());

                auditService.systemAction(COMPONENT, AuditEvent.AuditAction.ZOMBIE_DETECTED,
                        AuditEvent.Severity.WARN, AuditEvent.TargetType.CONTAINER,
                        ci.getProjectImage().getServiceName(),
                        ci.getProjectImage().getProject().getId(), ownerOf(ci),
                        "Container record not found in Docker, marked FAILED (worker: "
                                + worker.getWorkerName() + ")");
            }
        }

        logResult(worker, zombiesFound, dbRunning.size());
    }

    private void recoverFalseFailed(WorkerNode worker, Set<String> actualSet) {

        List<ContainerInstance> failed = containerInstanceRepository
                .findByWorkerNode_IdAndStatus(worker.getId(), ContainerInstance.InstanceStatus.FAILED);

        int recovered = 0;

        for (ContainerInstance ci : failed) {
            String dockerId = ci.getDockerContainerId();
            if (dockerId == null || dockerId.isBlank() || !actualSet.contains(dockerId)) {
                continue;
            }

            ci.setStatus(ContainerInstance.InstanceStatus.RUNNING);
            containerInstanceRepository.save(ci);
            recovered++;

            gatewayNotificationService.register(ci);

            log.info("[Reconcile] Recovered false-FAILED container on worker '{}': instanceId={}, dockerId={}, service={} -> RUNNING",
                    worker.getWorkerName(),
                    ci.getId(),
                    dockerId,
                    ci.getProjectImage().getServiceName());

            auditService.systemAction(COMPONENT, AuditEvent.AuditAction.CONTAINER_RECOVERED,
                    AuditEvent.Severity.INFO, AuditEvent.TargetType.CONTAINER,
                    ci.getProjectImage().getServiceName(),
                    ci.getProjectImage().getProject().getId(), ownerOf(ci),
                    "Running container falsely marked FAILED was recovered to RUNNING (worker: "
                            + worker.getWorkerName() + ")");
        }

        if (recovered > 0) {
            log.info("[Reconcile] Worker '{}': {} false-FAILED container(s) recovered to RUNNING.",
                    worker.getWorkerName(), recovered);
        }
    }

    private void reconcileStopping(WorkerNode worker, Set<String> actualSet) {
        List<ContainerInstance> stoppingInstances = containerInstanceRepository
                .findByWorkerNode_IdAndStatus(worker.getId(), ContainerInstance.InstanceStatus.STOPPING);

        for (ContainerInstance ci : stoppingInstances) {
            String dockerId = ci.getDockerContainerId();
            if (dockerId == null || dockerId.isBlank() || !actualSet.contains(dockerId)) {
                ci.setStatus(ContainerInstance.InstanceStatus.STOPPED);
                containerInstanceRepository.save(ci);
                log.info("[Reconcile] Container in STOPPING state confirmed stopped on worker '{}': instanceId={}, dockerId={} -> STOPPED",
                        worker.getWorkerName(), ci.getId(), dockerId);
            } else {
                log.warn("[Reconcile] Container in STOPPING state still running in Docker on worker '{}': instanceId={}, dockerId={}. Retrying stop/remove.",
                        worker.getWorkerName(), ci.getId(), dockerId);
                try {
                    workerHttpClient.stopAndRemoveContainer(worker, dockerId);
                } catch (Exception e) {
                    log.warn("[Reconcile] Failed to retry stopAndRemove for container {} on worker {}: {}",
                            dockerId, worker.getWorkerName(), e.getMessage());
                }
            }
        }
    }

    private void cleanFalseStopped(WorkerNode worker, Set<String> actualSet) {
        List<ContainerInstance> stoppedInstances = containerInstanceRepository
                .findByWorkerNode_IdAndStatus(worker.getId(), ContainerInstance.InstanceStatus.STOPPED);

        for (ContainerInstance ci : stoppedInstances) {
            String dockerId = ci.getDockerContainerId();
            if (dockerId != null && !dockerId.isBlank() && actualSet.contains(dockerId)) {
                log.warn("[Reconcile] Zombie container found running on worker '{}' but marked STOPPED in DB: instanceId={}, dockerId={}. Stopping in Docker.",
                        worker.getWorkerName(), ci.getId(), dockerId);
                gatewayNotificationService.deregister(ci);
                try {
                    workerHttpClient.stopAndRemoveContainer(worker, dockerId);
                } catch (Exception e) {
                    log.warn("[Reconcile] Failed to stop zombie STOPPED container {} on worker {}: {}",
                            dockerId, worker.getWorkerName(), e.getMessage());
                }
            }
        }
    }

    private String ownerOf(ContainerInstance ci) {
        try {
            return ci.getProjectImage().getProject().getOwner() != null
                    ? ci.getProjectImage().getProject().getOwner().getUsername() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void logResult(WorkerNode worker, int zombiesFound, int dbRunningCount) {
        if (zombiesFound > 0) {
            log.info("[Reconcile] Worker '{}': {} zombie record(s) reconciled.",
                    worker.getWorkerName(), zombiesFound);
        } else {
            log.debug("[Reconcile] Worker '{}': DB and Docker in sync ({} RUNNING).",
                    worker.getWorkerName(), dbRunningCount);
        }
    }
}
