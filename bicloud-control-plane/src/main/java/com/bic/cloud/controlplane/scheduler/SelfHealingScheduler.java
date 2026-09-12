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

    private static final long STARTUP_COOLDOWN_SECONDS = 60;
    private final Instant startupTime = Instant.now();

    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final Duration FAILURE_COOLDOWN = Duration.ofMinutes(5);

    private static final int CRASH_LOOP_THRESHOLD = 5;
    private static final Duration CRASH_LOOP_WINDOW = Duration.ofMinutes(5);

    private static final Duration PENDING_TIMEOUT = Duration.ofMinutes(20);

    // Intentionally non-transactional to avoid holding DB connections during HTTP worker calls
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

            if (desired <= 0 || image.isStoppedByUser()) {
                long activeCount = containerInstanceRepository.countByProjectImageAndStatusIn(
                        image, List.of(ContainerInstance.InstanceStatus.RUNNING,
                                       ContainerInstance.InstanceStatus.PENDING,
                                       ContainerInstance.InstanceStatus.STOPPING));
                if (activeCount > 0) {
                    log.info("[Self-Healing] Service '{}' in project '{}' is stopped/scaled to 0, but has {} active instance(s). Reconciling to 0.",
                            image.getServiceName(), image.getProject().getName(), activeCount);
                    deploymentService.scaleAsync(image.getId(), 0);
                }
                continue;
            }

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

    private String ownerOf(ProjectImage image) {
        try {
            return image.getProject().getOwner() != null
                    ? image.getProject().getOwner().getUsername() : null;
        } catch (Exception e) {
            return null;
        }
    }

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
