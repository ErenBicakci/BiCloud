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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deliberately NOT @Transactional: these flows call workers over HTTP (an image
 * pull can take minutes) and a transaction here would hold a DB connection for
 * that entire time - a handful of concurrent deploys could exhaust the pool and
 * freeze the whole CP. Each repository save commits on its own; consistency is
 * maintained by the reconciliation/self-healing loops, not by rollback.
 */
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
    private final WorkerStateRepository workerStateRepository;

    /**
     * Images with a deploy/scale currently executing. Guards against the same
     * service being deployed twice concurrently (double click, or self-healing
     * firing while a previous round is still queued on the executor).
     */
    private final Set<Long> imagesInFlight = ConcurrentHashMap.newKeySet();

    /**
     * Deploys on the deployment executor and returns immediately. The image is
     * re-loaded fully initialized because this runs outside any request session.
     */
    @Async("deploymentExecutor")
    public void deployAsync(Long imageId) {
        if (!imagesInFlight.add(imageId)) {
            log.info("[Async] Deploy already in flight for imageId={}. Skipping.", imageId);
            return;
        }
        try {
            projectImageRepository.findByIdForDeployment(imageId).ifPresentOrElse(
                    this::deploy,
                    () -> log.warn("[Async] Deploy skipped - image no longer exists: id={}", imageId));
        } catch (Exception e) {
            log.error("[Async] Deploy failed for imageId={}", imageId, e);
        } finally {
            imagesInFlight.remove(imageId);
        }
    }

    /** Async counterpart of {@link #scale} - see {@link #deployAsync}. */
    @Async("deploymentExecutor")
    public void scaleAsync(Long imageId, int newReplicas) {
        if (!imagesInFlight.add(imageId)) {
            log.info("[Async] Deploy already in flight for imageId={}. Skipping scale.", imageId);
            return;
        }
        try {
            projectImageRepository.findByIdForDeployment(imageId).ifPresentOrElse(
                    image -> scale(image, newReplicas),
                    () -> log.warn("[Async] Scale skipped - image no longer exists: id={}", imageId));
        } catch (Exception e) {
            log.error("[Async] Scale failed for imageId={}", imageId, e);
        } finally {
            imagesInFlight.remove(imageId);
        }
    }

    public void deploy(ProjectImage projectImage) {

        // PENDING counts as alive: those replicas are being created right now
        // (image pull in progress) - topping them up would double-deploy.
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

        // Flag FIRST: with desiredReplicas untouched, self-healing would
        // otherwise resurrect the service ~30s after the user undeployed it.
        for (ProjectImage image : projectImageRepository.findByProject_Id(projectId)) {
            if (!image.isStoppedByUser()) {
                image.setStoppedByUser(true);
                projectImageRepository.save(image);
            }
        }

        List<ContainerInstance> running = containerInstanceRepository.findRunningByProjectId(projectId);

        log.info("Undeploying {} running container(s) for project {}", running.size(), projectId);

        for (ContainerInstance instance : running) {
            stopAndRemoveInstance(instance);
        }
    }

    private void deployReplicas(ProjectImage projectImage, int count, int startIndex) {

        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < count; i++) {
            final int replicaIndex = startIndex + i;

            WorkerContainerCreateRequest request =
                    workerRequestMapper.toWorkerRequest(projectImage);

            int requiredCpuMillicores = request.getCpuLimitMillicores() != null
                    ? request.getCpuLimitMillicores() : 0;
            long requiredMemoryMb = request.getMemoryLimitMb() != null
                    ? request.getMemoryLimitMb() : 0;

            WorkerNode bestWorker = scoringService
                    .selectBestWorkerWithCapacity(requiredCpuMillicores, requiredMemoryMb)
                    .orElseGet(() -> scoringService.selectBestWorker()
                            .orElseThrow(() -> new NoAvailableWorkerException(
                                    "No worker available for replica " + (replicaIndex + 1)
                                            + " of " + projectImage.getServiceName())));

            // The platform contract is a single env: BICLOUD_MESH_BASE (users cannot override it).
            // Apps build other services' addresses as MESH_BASE + "/" + serviceName;
            // the address is a Docker DNS alias, identical on every worker.
            String projectName = projectImage.getProject().getName();
            Map<String, String> mergedEnv = new HashMap<>(
                    request.getEnv() != null ? request.getEnv() : Map.of());
            mergedEnv.put("BICLOUD_MESH_BASE", serviceDiscoveryService.meshBaseUrl(projectName));

            request.setEnv(mergedEnv);

            log.info("Deploying replica {}/{} of '{}' to worker '{}' (id={})",
                    replicaIndex + 1, projectImage.getDesiredReplicas(),
                    projectImage.getServiceName(),
                    bestWorker.getWorkerName(), bestWorker.getId());

            // PENDING row BEFORE the worker call: it reserves capacity for the
            // scheduler and tells self-healing/UI a replica is on its way while
            // the image pull runs. Committed immediately (no surrounding tx).
            ContainerInstance instance = containerInstanceRepository.save(
                    ContainerInstance.builder()
                            .projectImage(projectImage)
                            .workerNode(bestWorker)
                            .assignedPort(0)
                            .status(ContainerInstance.InstanceStatus.PENDING)
                            .build());

            try {
                WorkerContainerCreateResponse response =
                        workerHttpClient.createContainer(bestWorker, request);

                instance.setDockerContainerId(response.getContainerId());
                instance.setAssignedPort(response.getAssignedPort() != null ? response.getAssignedPort() : 0);
                instance.setContainerIp(response.getContainerIp());
                instance.setStatus(ContainerInstance.InstanceStatus.RUNNING);
                instance.setStartedAt(Instant.now());
                containerInstanceRepository.save(instance);

                // register with the gateway - the CP is the single authority
                gatewayNotificationService.register(instance);

                successCount++;

                log.info("ContainerInstance saved: containerId={}, worker={}, port={}",
                        response.getContainerId(),
                        bestWorker.getWorkerName(),
                        response.getAssignedPort());

            } catch (Exception e) {
                log.error("Failed to deploy replica {}/{} of '{}' to worker '{}'",
                        replicaIndex + 1, projectImage.getDesiredReplicas(),
                        projectImage.getServiceName(),
                        bestWorker.getWorkerName(), e);

                instance.setStatus(ContainerInstance.InstanceStatus.FAILED);
                containerInstanceRepository.save(instance);
                failureCount++;
            }
        }

        updateBackoffCounters(projectImage, successCount, failureCount);
    }

    /**
     * Self-healing retry backoff:
     *   - if even one replica succeeded -> reset the counter.
     *   - if all failed -> increment the counter, refresh the timestamp.
     *   - if nothing was attempted (count=0) -> leave untouched.
     *
     * SelfHealingScheduler reads these fields to decide on cooldown.
     */
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

        List<ContainerInstance> running = containerInstanceRepository
                .findByProjectImageAndStatus(projectImage, ContainerInstance.InstanceStatus.RUNNING);

        for (int i = 0; i < count && i < running.size(); i++) {
            stopAndRemoveInstance(running.get(i));
        }
    }

    public void stopAndRemove(ContainerInstance instance) {
        stopAndRemoveInstance(instance);
    }

    private void stopAndRemoveInstance(ContainerInstance instance) {
        // deregister from the gateway first - no requests should arrive while the container stops
        gatewayNotificationService.deregister(instance);

        try {
            workerHttpClient.stopAndRemoveContainer(
                    instance.getWorkerNode(), instance.getDockerContainerId());

            log.info("Container stopped & removed: {} on worker {}",
                    instance.getDockerContainerId(),
                    instance.getWorkerNode().getWorkerName());

        } catch (Exception e) {
            log.warn("Worker could not stop/remove container {} (may already be gone): {}",
                    instance.getDockerContainerId(), e.getMessage());
        }
        instance.setStatus(ContainerInstance.InstanceStatus.STOPPED);
        containerInstanceRepository.save(instance);
    }
}
