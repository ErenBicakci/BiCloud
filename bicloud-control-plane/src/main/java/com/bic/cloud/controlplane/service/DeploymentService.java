package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.client.WorkerHttpClient;
import com.bic.cloud.controlplane.dto.WorkerContainerCreateRequest;
import com.bic.cloud.controlplane.dto.WorkerContainerCreateResponse;
import com.bic.cloud.controlplane.exception.NoAvailableWorkerException;
import com.bic.cloud.controlplane.mapper.WorkerRequestMapper;
import com.bic.cloud.controlplane.model.ContainerInstance;
import com.bic.cloud.controlplane.model.ProjectImage;
import com.bic.cloud.controlplane.model.WorkerNode;
import com.bic.cloud.controlplane.model.WorkerState;
import com.bic.cloud.controlplane.repository.ContainerInstanceRepository;
import com.bic.cloud.controlplane.repository.ProjectImageRepository;
import com.bic.cloud.controlplane.repository.WorkerStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentService {

    private final WorkerHttpClient workerHttpClient;
    private final WorkerRequestMapper workerRequestMapper;
    private final WorkerScoringService scoringService;
    private final ContainerInstanceRepository containerInstanceRepository;
    private final ProjectImageRepository projectImageRepository;
    private final ServiceDiscoveryService serviceDiscoveryService;
    private final GatewayNotificationService gatewayNotificationService;
    /**
     * Reconciliation state machine per service:
     * 0 = IDLE
     * 1 = RUNNING
     * 2 = RUNNING_AND_QUEUED
     */
    private final ConcurrentHashMap<Long, AtomicInteger> serviceReconcileStates = new ConcurrentHashMap<>();

    @Async("deploymentExecutor")
    public void deployAsync(Long imageId) {
        triggerReconciliation(imageId);
    }

    @Async("deploymentExecutor")
    public void scaleAsync(Long imageId, int newReplicas) {
        triggerReconciliation(imageId);
    }

    private void triggerReconciliation(Long imageId) {
        AtomicInteger state = serviceReconcileStates.computeIfAbsent(imageId, k -> new AtomicInteger(0));

        while (true) {
            int current = state.get();
            if (current == 0) {
                if (state.compareAndSet(0, 1)) {
                    break;
                }
            } else if (current == 1) {
                if (state.compareAndSet(1, 2)) {
                    log.info("[Async] Work already in flight for imageId={}. Queued for subsequent reconciliation.", imageId);
                    return;
                }
            } else {
                log.info("[Async] Subsequent reconciliation already queued for imageId={}.", imageId);
                return;
            }
        }

        try {
            while (true) {
                projectImageRepository.findByIdForDeployment(imageId).ifPresentOrElse(
                        this::reconcileService,
                        () -> log.warn("[Async] Reconcile skipped - image no longer exists: id={}", imageId));

                if (state.compareAndSet(1, 0)) {
                    break;
                } else {
                    state.set(1);
                }
            }
        } catch (Exception e) {
            log.error("[Async] Reconciliation failed for imageId={}", imageId, e);
        } finally {
            state.set(0);
        }
    }

    public void reconcileService(ProjectImage projectImage) {
        ProjectImage latest = projectImageRepository.findByIdForDeployment(projectImage.getId()).orElse(projectImage);
        int effectiveDesired = latest.isStoppedByUser() ? 0 : latest.getDesiredReplicas();
        if (effectiveDesired <= 0) {
            long activeCount = containerInstanceRepository.countByProjectImageAndStatusIn(
                    latest, List.of(
                            ContainerInstance.InstanceStatus.RUNNING,
                            ContainerInstance.InstanceStatus.PENDING,
                            ContainerInstance.InstanceStatus.STOPPING));
            if (activeCount > 0) {
                log.info("Service '{}' is stopped or scaled to 0. Removing {} remaining active instance(s).",
                        latest.getServiceName(), activeCount);
                removeReplicas(latest, (int) activeCount);
            }
            return;
        }
        scale(latest, effectiveDesired);
    }

    public void deploy(ProjectImage projectImage) {
        if (projectImage.isStoppedByUser()) {
            log.info("Service '{}' is stopped by user. Skipping deploy.", projectImage.getServiceName());
            return;
        }

        long aliveCount = countAlive(projectImage);

        int needed = projectImage.getDesiredReplicas() - (int) aliveCount;

        if (needed <= 0) {
            log.info("Service '{}' already has {}/{} RUNNING/PENDING containers. Skipping.",
                    projectImage.getServiceName(), aliveCount, projectImage.getDesiredReplicas());
            return;
        }

        log.info("Deploying {} new replica(s) for '{}' (current={}, desired={})",
                needed, projectImage.getServiceName(), aliveCount, projectImage.getDesiredReplicas());

        deployReplicas(projectImage, needed, (int) aliveCount);
    }

    public void scale(ProjectImage projectImage, int newReplicas) {

        long currentAlive = countAlive(projectImage);

        if (newReplicas > currentAlive) {
            int toAdd = newReplicas - (int) currentAlive;
            log.info("Scaling UP '{}': {} -> {} (+{} replicas)",
                    projectImage.getServiceName(), currentAlive, newReplicas, toAdd);
            deployReplicas(projectImage, toAdd, (int) currentAlive);

        } else if (newReplicas < currentAlive) {
            int toRemove = (int) currentAlive - newReplicas;
            log.info("Scaling DOWN '{}': {} -> {} (-{} replicas)",
                    projectImage.getServiceName(), currentAlive, newReplicas, toRemove);
            removeReplicas(projectImage, toRemove);

        } else {
            log.info("Service '{}' already at {} replicas. No action needed.",
                    projectImage.getServiceName(), newReplicas);
        }
    }

    private long countAlive(ProjectImage projectImage) {
        return containerInstanceRepository
                .countByProjectImageAndStatus(projectImage, ContainerInstance.InstanceStatus.RUNNING)
             + containerInstanceRepository
                .countByProjectImageAndStatus(projectImage, ContainerInstance.InstanceStatus.PENDING);
    }

    public void undeployProject(Long projectId) {
        for (ProjectImage image : projectImageRepository.findByProject_Id(projectId)) {
            image.setStoppedByUser(true);
            projectImageRepository.save(image);
        }

        List<ContainerInstance> activeInstances = containerInstanceRepository.findActiveByProjectId(projectId);

        log.info("Undeploying {} active container(s) for project {}", activeInstances.size(), projectId);

        for (ContainerInstance instance : activeInstances) {
            if (instance.getStatus() == ContainerInstance.InstanceStatus.PENDING
                    && (instance.getDockerContainerId() == null || instance.getDockerContainerId().isBlank())) {
                instance.setStatus(ContainerInstance.InstanceStatus.STOPPED);
                containerInstanceRepository.save(instance);
            } else {
                stopAndRemoveInstance(instance);
            }
        }
    }

    private void deployReplicas(ProjectImage projectImage, int count, int startIndex) {

        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < count; i++) {
            final int replicaIndex = startIndex + i;

            ProjectImage currentImage = projectImageRepository.findByIdForDeployment(projectImage.getId()).orElse(null);
            int effectiveDesired = currentImage != null && !currentImage.isStoppedByUser()
                    ? currentImage.getDesiredReplicas() : 0;

            if (currentImage == null || currentImage.isStoppedByUser()
                    || effectiveDesired <= countAlive(currentImage)) {
                log.info("[Deploy] Service '{}' deployment cancelled or target reached (stoppedByUser={}, desired={}, alive={}). Aborting remaining replicas.",
                        projectImage.getServiceName(),
                        currentImage != null && currentImage.isStoppedByUser(),
                        effectiveDesired,
                        currentImage != null ? countAlive(currentImage) : 0);
                break;
            }

            WorkerContainerCreateRequest request =
                    workerRequestMapper.toWorkerRequest(currentImage);

            int requiredCpuMillicores = request.getCpuLimitMillicores() != null
                    ? request.getCpuLimitMillicores() : 0;
            long requiredMemoryMb = request.getMemoryLimitMb() != null
                    ? request.getMemoryLimitMb() : 0;

            Optional<ContainerInstance> reserved = scoringService
                    .selectAndReserveWorker(currentImage, requiredCpuMillicores, requiredMemoryMb);

            if (reserved.isEmpty()) {
                log.warn("Insufficient cluster capacity to schedule replica {}/{} of '{}' (required: {}m CPU, {}MB RAM)",
                        replicaIndex + 1, currentImage.getDesiredReplicas(),
                        currentImage.getServiceName(), requiredCpuMillicores, requiredMemoryMb);
                failureCount++;
                break;
            }

            ContainerInstance instance = reserved.get();
            WorkerNode bestWorker = instance.getWorkerNode();

            request.setInstanceId(instance.getId().toString());

            String projectName = currentImage.getProject().getName();
            Map<String, String> mergedEnv = new HashMap<>(
                    request.getEnv() != null ? request.getEnv() : Map.of());
            mergedEnv.put("BICLOUD_MESH_BASE", serviceDiscoveryService.meshBaseUrl(projectName));
            request.setEnv(mergedEnv);

            log.info("Deploying replica {}/{} of '{}' to worker '{}' (id={})",
                    replicaIndex + 1, currentImage.getDesiredReplicas(),
                    currentImage.getServiceName(),
                    bestWorker.getWorkerName(), bestWorker.getId());

            try {
                WorkerContainerCreateResponse response =
                        workerHttpClient.createContainer(bestWorker, request);

                ProjectImage freshImage = projectImageRepository.findByIdForDeployment(projectImage.getId()).orElse(null);
                int freshEffectiveDesired = freshImage != null && !freshImage.isStoppedByUser()
                        ? freshImage.getDesiredReplicas() : 0;

                if (freshImage == null || freshImage.isStoppedByUser() || freshEffectiveDesired <= 0) {
                    log.warn("[Deploy] Service '{}' was stopped while container was creating. Cleaning up container {} immediately.",
                            currentImage.getServiceName(), response.getContainerId());
                    instance.setDockerContainerId(response.getContainerId());
                    stopAndRemoveInstance(instance);
                    break;
                }

                instance.setDockerContainerId(response.getContainerId());
                instance.setContainerIp(response.getContainerIp());
                instance.setStatus(ContainerInstance.InstanceStatus.RUNNING);
                instance.setStartedAt(Instant.now());
                containerInstanceRepository.save(instance);

                gatewayNotificationService.register(instance);

                successCount++;

                log.info("ContainerInstance saved: containerId={}, worker={}",
                        response.getContainerId(),
                        bestWorker.getWorkerName());

            } catch (Exception e) {
                log.error("Failed to deploy replica {}/{} of '{}' to worker '{}'",
                        replicaIndex + 1, currentImage.getDesiredReplicas(),
                        currentImage.getServiceName(),
                        bestWorker.getWorkerName(), e);

                instance.setStatus(ContainerInstance.InstanceStatus.FAILED);
                containerInstanceRepository.save(instance);
                failureCount++;
            }
        }

        updateBackoffCounters(projectImage, successCount, failureCount);
    }

    private void updateBackoffCounters(ProjectImage image, int successCount, int failureCount) {

        if (successCount + failureCount == 0) {
            return;
        }

        if (successCount > 0) {
            if (image.getConsecutiveDeployFailures() > 0 || image.getLastDeployFailureAt() != null) {
                log.info("[Backoff] Service '{}' recovered - resetting failure counter (was={})",
                        image.getServiceName(), image.getConsecutiveDeployFailures());
                image.setConsecutiveDeployFailures(0);
                image.setLastDeployFailureAt(null);
                projectImageRepository.save(image);
            }
            return;
        }

        int newCount = image.getConsecutiveDeployFailures() + 1;
        image.setConsecutiveDeployFailures(newCount);
        image.setLastDeployFailureAt(Instant.now());
        projectImageRepository.save(image);

        log.warn("[Backoff] Service '{}' deploy failed ({} consecutive failure(s))",
                image.getServiceName(), newCount);
    }

    private void removeReplicas(ProjectImage projectImage, int count) {

        List<ContainerInstance> active = containerInstanceRepository
                .findByProjectImageAndStatusIn(projectImage, List.of(
                        ContainerInstance.InstanceStatus.RUNNING,
                        ContainerInstance.InstanceStatus.PENDING,
                        ContainerInstance.InstanceStatus.STOPPING));

        for (int i = 0; i < count && i < active.size(); i++) {
            stopAndRemoveInstance(active.get(i));
        }
    }

    public void stopAndRemove(ContainerInstance instance) {
        stopAndRemoveInstance(instance);
    }

    private void stopAndRemoveInstance(ContainerInstance instance) {
        gatewayNotificationService.deregister(instance);

        instance.setStatus(ContainerInstance.InstanceStatus.STOPPING);
        containerInstanceRepository.save(instance);

        if (instance.getDockerContainerId() == null || instance.getDockerContainerId().isBlank()) {
            instance.setStatus(ContainerInstance.InstanceStatus.STOPPED);
            containerInstanceRepository.save(instance);
            return;
        }

        try {
            workerHttpClient.stopAndRemoveContainer(
                    instance.getWorkerNode(), instance.getDockerContainerId());

            log.info("Container stopped & removed: {} on worker {}",
                    instance.getDockerContainerId(),
                    instance.getWorkerNode().getWorkerName());

            instance.setStatus(ContainerInstance.InstanceStatus.STOPPED);
            containerInstanceRepository.save(instance);

        } catch (Exception e) {
            log.warn("Worker could not stop/remove container {} (leaving in STOPPING for reconciler): {}",
                    instance.getDockerContainerId(), e.getMessage());
        }
    }
}
