package com.bic.cloud.controlplane.service;

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

/**
 * Compares container snapshots coming from workers against the DB.
 *
 * Scenario: a container looks RUNNING in the DB but is gone from Docker
 * (removed manually by an operator, killed unexpectedly by the daemon, etc.).
 * These zombie records fool self-healing (it thinks replicas are fine) and
 * the user sees a dead service as healthy.
 *
 * Every 20 seconds the worker sends the IDs of all running containers with
 * the {@code bicloud.managed=true} label. Here:
 *   1. fetch the worker's RUNNING containers from the DB
 *   2. anything missing from the snapshot -> mark FAILED
 *   3. self-healing spawns a replacement on its next cycle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContainerReconciliationService {

    /**
     * A snapshot goes stale between the moment the worker lists containers and
     * the moment it reaches the CP (network latency + stats collection time).
     * Containers created inside that window can't be on the list; treating them
     * as zombies causes an endless restart loop with self-healing. So fresh
     * records are left alone.
     */
    private static final long NEW_INSTANCE_GRACE_SECONDS = 60;

    private final WorkerNodeRepository workerNodeRepository;
    private final ContainerInstanceRepository containerInstanceRepository;
    private final GatewayNotificationService gatewayNotificationService;
    private final AuditService auditService;

    private static final String COMPONENT = "reconciler";

    @Transactional
    public void reconcile(UUID workerId, List<String> actualRunningContainerIds) {

        WorkerNode worker = workerNodeRepository.findById(workerId)
                .orElseThrow(() -> new WorkerNotFoundException(workerId));

        Set<String> actualSet = actualRunningContainerIds == null
                ? new HashSet<>()
                : new HashSet<>(actualRunningContainerIds);

        recoverFalseFailed(worker, actualSet);

        List<ContainerInstance> dbRunning = containerInstanceRepository
                .findRunningByWorkerNodeId(workerId);

        Instant graceCutoff = Instant.now().minusSeconds(NEW_INSTANCE_GRACE_SECONDS);
        int zombiesFound = 0;

        for (ContainerInstance ci : dbRunning) {
            String dockerId = ci.getDockerContainerId();

            if (dockerId == null || dockerId.isBlank()) {
                continue;
            }

            // grace period: might have started after the snapshot, leave it to
            // the next round. startedAt marks the actual RUNNING transition;
            // createdAt is the PENDING reservation, which can be minutes older
            // (image pull) and would defeat the grace check here.
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

    /**
     * Recovery in the opposite direction: records that look FAILED in the DB
     * but are still running in Docker according to the snapshot go back to RUNNING.
     *
     * This typically happens when a worker is briefly considered OFFLINE
     * (clock skew, short network blip): WorkerHealthScheduler marks its
     * containers FAILED, the worker comes back, and the containers never
     * actually died. Without recovery, self-healing spawns duplicates and the
     * real containers pile up orphaned.
     */
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

            // the gateway entry wasn't removed when it got marked FAILED, but make sure -
            // register is idempotent (resyncAll re-registers the same way).
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

    /** Lazy owner access - safe inside the @Transactional reconcile. */
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
