package com.bic.cloud.controlplane.scheduler;

import com.bic.cloud.controlplane.model.AuditEvent;
import com.bic.cloud.controlplane.model.ContainerInstance;
import com.bic.cloud.controlplane.model.ProjectImage;
import com.bic.cloud.controlplane.repository.ContainerInstanceRepository;
import com.bic.cloud.controlplane.repository.ProjectImageRepository;
import com.bic.cloud.controlplane.service.AuditService;
import com.bic.cloud.controlplane.service.DeploymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SelfHealingScheduler {

    private final ProjectImageRepository projectImageRepository;
    private final ContainerInstanceRepository containerInstanceRepository;
    private final DeploymentService deploymentService;
    private final AuditService auditService;

    private static final String COMPONENT = "self-healing";

    //give workers some time to register after startup
    private static final long STARTUP_COOLDOWN_SECONDS = 60;
    private final Instant startupTime = Instant.now();

    /**
     * Max consecutive failures before self-healing stops retrying a service and puts it in cooldown.
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    /**
     * A service that hits MAX_CONSECUTIVE_FAILURES is left alone for this long.
     * It stays passive until the operator deploys manually or the window expires.
     */
    private static final Duration FAILURE_COOLDOWN = Duration.ofMinutes(5);

    /**
     * Crash-loop detection: when a deploy SUCCEEDS but the container dies later,
     * the deploy-failure counter never increments (a successful start resets it).
     * So we additionally count FAILED instances within CRASH_LOOP_WINDOW; past the
     * threshold the service enters the same cooldown mechanism (a simplified
     * version of Kubernetes' CrashLoopBackOff).
     */
    private static final int CRASH_LOOP_THRESHOLD = 5;
    private static final Duration CRASH_LOOP_WINDOW = Duration.ofMinutes(5);

    /**
     * A PENDING instance older than this is an orphan (its deploy thread is
     * gone - the worker's pull timeout is 10 min, the CP's HTTP read timeout
     * 15 min, so nothing legitimate is still in flight past 20).
     */
    private static final Duration PENDING_TIMEOUT = Duration.ofMinutes(20);

    // NOT @Transactional: deploy/scale below reach workers over HTTP (an image
    // pull can take minutes) and a transaction would pin a DB connection for the
    // whole loop. findAllWithProject fetch-joins everything the loop touches.
    @Scheduled(fixedDelay = 30000)
    public void reconcile() {

        long secondsSinceStartup = Instant.now().getEpochSecond() - startupTime.getEpochSecond();
        if (secondsSinceStartup < STARTUP_COOLDOWN_SECONDS) {
            log.info("[Self-Healing] Skipping reconcile - startup cooldown active ({}/{}s)",
                    secondsSinceStartup, STARTUP_COOLDOWN_SECONDS);
            return;
        }

        failStalePendingInstances();

        List<ProjectImage> allImages = projectImageRepository.findAllWithProject();

        for (ProjectImage image : allImages) {

            int desired = image.getDesiredReplicas();

            // desired=0 (scaled to zero) or an explicit undeploy both mean the
            // user stopped the service on purpose, don't touch it
            if (desired <= 0 || image.isStoppedByUser()) {
                continue;
            }

            // PENDING counts as alive: those replicas are being created right now
            long runningCount = containerInstanceRepository
                    .countByProjectImageAndStatus(image, ContainerInstance.InstanceStatus.RUNNING)
                    + containerInstanceRepository
                    .countByProjectImageAndStatus(image, ContainerInstance.InstanceStatus.PENDING);

            if (runningCount < desired) {

                if (isInFailureCooldown(image)) {
                    log.warn("[Self-Healing] Service '{}' in project '{}' is in COOLDOWN ({} consecutive failures, last={}). Skipping.",
                            image.getServiceName(),
                            image.getProject().getName(),
                            image.getConsecutiveDeployFailures(),
                            image.getLastDeployFailureAt());
                    continue;
                }

                if (detectCrashLoop(image)) {
                    continue;
                }

                log.info("[Self-Healing] Service '{}' in project '{}': running={}, desired={} -> spawning {} replacement(s)",
                        image.getServiceName(),
                        image.getProject().getName(),
                        runningCount,
                        desired,
                        desired - runningCount);

                try {
                    // async: a long image pull must not stall this loop for the
                    // other tenants; PENDING counting keeps the next cycles from
                    // re-firing while the deploy is in flight
                    deploymentService.deployAsync(image.getId());
                    auditService.systemAction(COMPONENT, AuditEvent.AuditAction.SELF_HEALING_DEPLOY,
                            AuditEvent.Severity.WARN, AuditEvent.TargetType.SERVICE,
                            image.getServiceName(), image.getProject().getId(), ownerOf(image),
                            "Self-healing kicked in: restarting " + (desired - runningCount)
                                    + " missing replica(s) (" + runningCount + "/" + desired + ")");
                } catch (Exception e) {
                    log.error("[Self-Healing] Failed to reconcile service '{}' in project '{}': {}",
                            image.getServiceName(),
                            image.getProject().getName(),
                            e.getMessage(), e);
                }

            } else if (runningCount > desired) {

                // Excess replicas: after a false-FAILED recovery (reconcile) both the
                // recovered container and its replacement may be running at the same
                // time. Scale back down to the desired count.
                log.info("[Self-Healing] Service '{}' in project '{}': running={}, desired={} -> scaling down {} excess replica(s)",
                        image.getServiceName(),
                        image.getProject().getName(),
                        runningCount,
                        desired,
                        runningCount - desired);

                try {
                    long excess = runningCount - desired;
                    deploymentService.scaleAsync(image.getId(), desired);
                    auditService.systemAction(COMPONENT, AuditEvent.AuditAction.EXCESS_SCALED_DOWN,
                            AuditEvent.Severity.INFO, AuditEvent.TargetType.SERVICE,
                            image.getServiceName(), image.getProject().getId(), ownerOf(image),
                            "Scaled down " + excess + " excess replica(s) to target (" + desired + ")");
                } catch (Exception e) {
                    log.error("[Self-Healing] Failed to scale down service '{}' in project '{}': {}",
                            image.getServiceName(),
                            image.getProject().getName(),
                            e.getMessage(), e);
                }
            }
        }
    }

    /**
     * PENDING is a promise that a deploy thread is working on the replica. Two
     * cases break that promise and would freeze the desired count forever,
     * because PENDING counts as alive:
     *   - rows created before this CP process started (the thread died with
     *     the previous JVM)
     *   - rows older than PENDING_TIMEOUT (every legitimate path has long
     *     timed out)
     * Both go to FAILED so healing can replace them.
     */
    private void failStalePendingInstances() {

        List<ContainerInstance> pendings = containerInstanceRepository
                .findAllByStatus(ContainerInstance.InstanceStatus.PENDING);

        Instant timeoutCutoff = Instant.now().minus(PENDING_TIMEOUT);

        for (ContainerInstance ci : pendings) {
            Instant created = ci.getCreatedAt();
            if (created == null) {
                continue;
            }

            boolean orphanOfPreviousRun = created.isBefore(startupTime);
            boolean timedOut = created.isBefore(timeoutCutoff);

            if (orphanOfPreviousRun || timedOut) {
                ci.setStatus(ContainerInstance.InstanceStatus.FAILED);
                containerInstanceRepository.save(ci);

                log.warn("[Self-Healing] Stale PENDING instance marked FAILED: instanceId={}, service={}, created={} ({})",
                        ci.getId(),
                        ci.getProjectImage().getServiceName(),
                        created,
                        orphanOfPreviousRun ? "predates CP startup" : "older than " + PENDING_TIMEOUT.toMinutes() + " min");
            }
        }
    }

    /**
     * Catches a service that produced more FAILED instances than the threshold
     * within CRASH_LOOP_WINDOW and disables it by filling the existing cooldown
     * fields. The cooldown badge in the UI, the "Reset Cooldown" button and the
     * isInFailureCooldown window all keep working without any extra mechanism.
     */
    private boolean detectCrashLoop(ProjectImage image) {

        long recentFailures = containerInstanceRepository
                .countByProjectImageAndStatusAndCreatedAtAfter(
                        image,
                        ContainerInstance.InstanceStatus.FAILED,
                        Instant.now().minus(CRASH_LOOP_WINDOW));

        if (recentFailures < CRASH_LOOP_THRESHOLD) {
            return false;
        }

        log.warn("[Self-Healing] CRASH LOOP detected for service '{}' in project '{}': " +
                 "{} FAILED instance(s) in the last {} min. Entering cooldown - " +
                 "fix the image/config, then use 'Reset Cooldown'.",
                image.getServiceName(),
                image.getProject().getName(),
                recentFailures,
                CRASH_LOOP_WINDOW.toMinutes());

        // trigger the existing backoff mechanism: badge + 5 min hands-off + manual reset
        image.setConsecutiveDeployFailures(
                Math.max(image.getConsecutiveDeployFailures(), MAX_CONSECUTIVE_FAILURES));
        image.setLastDeployFailureAt(Instant.now());
        projectImageRepository.save(image);

        auditService.systemAction(COMPONENT, AuditEvent.AuditAction.CRASH_LOOP_DETECTED,
                AuditEvent.Severity.WARN, AuditEvent.TargetType.SERVICE,
                image.getServiceName(), image.getProject().getId(), ownerOf(image),
                "Crash loop detected: " + recentFailures + " failures in the last "
                        + CRASH_LOOP_WINDOW.toMinutes() + " min. Service put in cooldown.");

        return true;
    }

    /** Owner is fetch-joined by findAllWithProject; the guard stays just in case. */
    private String ownerOf(ProjectImage image) {
        try {
            return image.getProject().getOwner() != null
                    ? image.getProject().getOwner().getUsername() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Cooldown is active when the service reached MAX_CONSECUTIVE_FAILURES and
     * the last failure is more recent than FAILURE_COOLDOWN.
     *
     * Once the window expires, or the operator deploys manually
     * (DeploymentService.updateBackoffCounters resets the counter on the first
     * successful replica), the service rejoins self-healing.
     */
    private boolean isInFailureCooldown(com.bic.cloud.controlplane.model.ProjectImage image) {

        if (image.getConsecutiveDeployFailures() < MAX_CONSECUTIVE_FAILURES) {
            return false;
        }
        if (image.getLastDeployFailureAt() == null) {
            return false;
        }

        Duration sinceFailure = Duration.between(image.getLastDeployFailureAt(), Instant.now());
        return sinceFailure.compareTo(FAILURE_COOLDOWN) < 0;
    }
}
