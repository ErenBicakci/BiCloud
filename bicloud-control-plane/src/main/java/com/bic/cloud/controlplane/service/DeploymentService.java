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
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public void deploy(ProjectImage projectImage) {

        long runningCount = containerInstanceRepository
                .countByProjectImageAndStatus(projectImage, ContainerInstance.InstanceStatus.RUNNING);

        int needed = projectImage.getDesiredReplicas() - (int) runningCount;

        if (needed <= 0) {
            log.info("Service '{}' already has {}/{} RUNNING containers. Skipping.",
                    projectImage.getServiceName(), runningCount, projectImage.getDesiredReplicas());
            return;
        }

        log.info("Deploying {} new replica(s) for '{}' (current={}, desired={})",
                needed, projectImage.getServiceName(), runningCount, projectImage.getDesiredReplicas());

        deployReplicas(projectImage, needed, (int) runningCount);
    }

    public void scale(ProjectImage projectImage, int newReplicas) {

        long currentRunning = containerInstanceRepository
                .countByProjectImageAndStatus(projectImage, ContainerInstance.InstanceStatus.RUNNING);

        if (newReplicas > currentRunning) {
            int toAdd = newReplicas - (int) currentRunning;
            log.info("Scaling UP '{}': {} -> {} (+{} replicas)",
                    projectImage.getServiceName(), currentRunning, newReplicas, toAdd);
            deployReplicas(projectImage, toAdd, (int) currentRunning);

        } else if (newReplicas < currentRunning) {
            int toRemove = (int) currentRunning - newReplicas;
            log.info("Scaling DOWN '{}': {} -> {} (-{} replicas)",
                    projectImage.getServiceName(), currentRunning, newReplicas, toRemove);
            removeReplicas(projectImage, toRemove);

        } else {
            log.info("Service '{}' already at {} replicas. No action needed.",
                    projectImage.getServiceName(), newReplicas);
        }
    }

    public void undeployProject(Long projectId) {

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

            try {
                WorkerContainerCreateResponse response =
                        workerHttpClient.createContainer(bestWorker, request);

                ContainerInstance instance = ContainerInstance.builder()
                        .dockerContainerId(response.getContainerId())
                        .projectImage(projectImage)
                        .workerNode(bestWorker)
                        .assignedPort(response.getAssignedPort() != null ? response.getAssignedPort() : 0)
                        .containerIp(response.getContainerIp())
                        .status(ContainerInstance.InstanceStatus.RUNNING)
                        .build();

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

                ContainerInstance failedInstance = ContainerInstance.builder()
                        .projectImage(projectImage)
                        .workerNode(bestWorker)
                        .assignedPort(0)
                        .status(ContainerInstance.InstanceStatus.FAILED)
                        .build();

                containerInstanceRepository.save(failedInstance);
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
