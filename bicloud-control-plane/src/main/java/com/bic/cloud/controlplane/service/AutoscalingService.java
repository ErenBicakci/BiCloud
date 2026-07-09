package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.model.AuditEvent;
import com.bic.cloud.controlplane.model.ContainerInstance;
import com.bic.cloud.controlplane.model.ProjectImage;
import com.bic.cloud.controlplane.repository.ContainerInstanceRepository;
import com.bic.cloud.controlplane.repository.ProjectImageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoscalingService {

    private static final String COMPONENT = "autoscaler";
    private static final Duration STALE_METRIC_AFTER = Duration.ofSeconds(90);
    private static final int DECISION_SAMPLE_COUNT = 4;

    private final ProjectImageRepository projectImageRepository;
    private final ContainerInstanceRepository containerInstanceRepository;
    private final ContainerMetricsService containerMetricsService;
    private final DeploymentService deploymentService;
    private final AuditService auditService;
    private final Map<Long, Deque<Double>> cpuUtilizationHistory = new ConcurrentHashMap<>();

    public void reconcileAll() {
        List<ProjectImage> images = projectImageRepository.findAllWithProject();
        evictDeletedServiceHistories(images);

        for (ProjectImage image : images) {
            if (!image.isAutoscalingEnabled() || image.isStoppedByUser()) {
                cpuUtilizationHistory.remove(image.getId());
                continue;
            }

            if (!isPolicyUsable(image)) {
                continue;
            }

            try {
                reconcile(image);
            } catch (Exception e) {
                log.error("[Autoscaler] Failed to reconcile service '{}' in project '{}': {}",
                        image.getServiceName(),
                        image.getProject().getName(),
                        e.getMessage(), e);
            }
        }
    }

    void reconcile(ProjectImage image) {
        long pending = containerInstanceRepository
                .countByProjectImageAndStatus(image, ContainerInstance.InstanceStatus.PENDING);

        if (pending > 0) {
            log.debug("[Autoscaler] Skipping '{}': {} pending replica(s).",
                    image.getServiceName(), pending);
            return;
        }

        int desired = image.getDesiredReplicas();
        int min = image.getMinReplicas();
        int max = image.getMaxReplicas();
        int boundedDesired = Math.max(min, Math.min(max, desired));

        if (desired != boundedDesired) {
            scale(image, boundedDesired, "desired replicas outside autoscaling bounds", null);
            return;
        }

        List<ContainerInstance> running = containerInstanceRepository
                .findByProjectImageAndStatus(image, ContainerInstance.InstanceStatus.RUNNING);

        if (running.size() != desired) {
            log.debug("[Autoscaler] Skipping '{}': running={} desired={} (waiting for reconciliation).",
                    image.getServiceName(), running.size(), desired);
            return;
        }

        OptionalDouble currentAverageCpu = averageCpuUtilizationPercent(image, running, Instant.now());
        if (currentAverageCpu.isEmpty()) {
            log.debug("[Autoscaler] Skipping '{}': no fresh CPU metrics.", image.getServiceName());
            return;
        }

        double windowAverageCpu = recordAndAverage(image.getId(), currentAverageCpu.getAsDouble());
        if (sampleCount(image.getId()) < DECISION_SAMPLE_COUNT) {
            log.debug("[Autoscaler] Service '{}': collected {}/{} CPU sample(s), waiting before scaling.",
                    image.getServiceName(), sampleCount(image.getId()), DECISION_SAMPLE_COUNT);
            return;
        }

        if (windowAverageCpu > image.getTargetCpuPercent() && desired < max) {
            if (!cooldownElapsed(image, Duration.ofSeconds(image.getScaleUpCooldownSeconds()))) {
                return;
            }
            scale(image, desired + 1, "4-round average CPU above target", windowAverageCpu);
        } else if (windowAverageCpu < image.getScaleDownCpuPercent() && desired > min) {
            if (!cooldownElapsed(image, Duration.ofSeconds(image.getScaleDownCooldownSeconds()))) {
                return;
            }
            scale(image, desired - 1, "4-round average CPU below scale-down threshold", windowAverageCpu);
        }
    }

    private OptionalDouble averageCpuUtilizationPercent(ProjectImage image,
                                                        List<ContainerInstance> running,
                                                        Instant now) {
        Instant cutoff = now.minus(STALE_METRIC_AFTER);
        double cpuCapacityPercent = image.getCpuLimit() != null && image.getCpuLimit() > 0
                ? image.getCpuLimit() * 100.0
                : 100.0;

        return running.stream()
                .map(ContainerInstance::getDockerContainerId)
                .filter(id -> id != null && !id.isBlank())
                .map(containerMetricsService::getLatest)
                .filter(point -> point != null && !point.at().isBefore(cutoff))
                .mapToDouble(point -> (point.cpuPercent() / cpuCapacityPercent) * 100.0)
                .average();
    }

    private double recordAndAverage(Long imageId, double cpuUtilizationPercent) {
        Deque<Double> samples = cpuUtilizationHistory.computeIfAbsent(imageId, ignored -> new ArrayDeque<>());
        synchronized (samples) {
            samples.addLast(cpuUtilizationPercent);
            while (samples.size() > DECISION_SAMPLE_COUNT) {
                samples.removeFirst();
            }
            return samples.stream().mapToDouble(Double::doubleValue).average().orElse(cpuUtilizationPercent);
        }
    }

    private int sampleCount(Long imageId) {
        Deque<Double> samples = cpuUtilizationHistory.get(imageId);
        if (samples == null) {
            return 0;
        }
        synchronized (samples) {
            return samples.size();
        }
    }

    private void evictDeletedServiceHistories(List<ProjectImage> images) {
        Set<Long> existingImageIds = images.stream()
                .map(ProjectImage::getId)
                .collect(Collectors.toSet());
        cpuUtilizationHistory.keySet().removeIf(id -> !existingImageIds.contains(id));
    }

    private boolean cooldownElapsed(ProjectImage image, Duration cooldown) {
        if (image.getLastAutoscaledAt() == null) {
            return true;
        }
        return Duration.between(image.getLastAutoscaledAt(), Instant.now()).compareTo(cooldown) >= 0;
    }

    private void scale(ProjectImage image, int newReplicas, String reason, Double cpuUtilization) {
        int oldReplicas = image.getDesiredReplicas();
        if (oldReplicas == newReplicas) {
            return;
        }

        image.setDesiredReplicas(newReplicas);
        image.setStoppedByUser(false);
        image.setLastAutoscaledAt(Instant.now());
        projectImageRepository.save(image);

        deploymentService.scaleAsync(image.getId(), newReplicas);

        String cpuSuffix = cpuUtilization != null
                ? ", avg CPU=" + String.format(Locale.ROOT, "%.1f", cpuUtilization) + "%"
                : "";

        log.info("[Autoscaler] Service '{}' in project '{}': {} -> {} replicas ({}){}",
                image.getServiceName(),
                image.getProject().getName(),
                oldReplicas,
                newReplicas,
                reason,
                cpuSuffix);

        auditService.systemAction(COMPONENT, AuditEvent.AuditAction.AUTOSCALING_SCALED,
                AuditEvent.Severity.INFO, AuditEvent.TargetType.SERVICE,
                image.getServiceName(), image.getProject().getId(), ownerOf(image),
                "Autoscaled " + oldReplicas + " -> " + newReplicas
                        + " replica(s): " + reason + cpuSuffix);
    }

    private boolean isPolicyUsable(ProjectImage image) {
        boolean valid = image.getMinReplicas() >= 1
                && image.getMaxReplicas() <= 10
                && image.getMinReplicas() <= image.getMaxReplicas()
                && image.getTargetCpuPercent() >= 1
                && image.getTargetCpuPercent() <= 100
                && image.getScaleDownCpuPercent() >= 1
                && image.getScaleDownCpuPercent() < image.getTargetCpuPercent()
                && image.getScaleUpCooldownSeconds() >= 15
                && image.getScaleDownCooldownSeconds() >= 15;

        if (!valid) {
            log.warn("[Autoscaler] Service '{}' in project '{}' has an invalid autoscaling policy. Skipping.",
                    image.getServiceName(), image.getProject().getName());
        }
        return valid;
    }

    private String ownerOf(ProjectImage image) {
        try {
            return image.getProject().getOwner() != null
                    ? image.getProject().getOwner().getUsername() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
