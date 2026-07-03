package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.dto.UpdateProjectImageDto;
import com.bic.cloud.controlplane.exception.NoImagesConfiguredException;
import com.bic.cloud.controlplane.model.AuditEvent;
import com.bic.cloud.controlplane.model.ContainerInstance;
import com.bic.cloud.controlplane.model.ProjectImage;
import com.bic.cloud.controlplane.model.UserProject;
import com.bic.cloud.controlplane.repository.ContainerInstanceRepository;
import com.bic.cloud.controlplane.repository.ProjectImageRepository;
import com.bic.cloud.controlplane.repository.UserProjectRepository;
import com.bic.cloud.controlplane.security.BicloudUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;

/**
 * Methods here mix DB writes with worker HTTP calls (stop, remove, create -
 * the last one can pull an image for minutes). None of them are @Transactional:
 * a transaction spanning the HTTP call would pin a DB connection for its whole
 * duration. Instead, the record mutations that must be atomic run in a narrow
 * {@link TransactionTemplate} block and the HTTP calls stay outside.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestrationService {

    private final ProjectService projectService;
    private final ProjectImageService projectImageService;
    private final ProjectImageRepository projectImageRepository;
    private final ContainerInstanceRepository containerInstanceRepository;
    private final UserProjectRepository userProjectRepository;
    private final DeploymentService deploymentService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public void deployProject(Long projectId, BicloudUserDetails caller) {

        UserProject project = projectService.findById(projectId);
        projectService.assertOwnerOrAdmin(project, caller);

        List<ProjectImage> images = projectImageRepository.findByProject_Id(projectId);

        if (images.isEmpty()) {
            throw new NoImagesConfiguredException(project.getName());
        }

        log.info("Deploying project '{}': {} image(s) queued",
                project.getName(), images.size());

        // fire-and-return: the API answers 202 immediately, containers appear
        // as PENDING and the executor brings them to RUNNING
        for (ProjectImage image : images) {
            if (image.isStoppedByUser()) {
                image.setStoppedByUser(false);
                projectImageRepository.save(image);
            }
            deploymentService.deployAsync(image.getId());
        }

        auditService.userAction(caller, AuditEvent.AuditAction.PROJECT_DEPLOYED,
                AuditEvent.TargetType.PROJECT, project.getName(), project,
                "Deployment of " + images.size() + " service(s) accepted");
    }

    public void undeployProject(Long projectId, BicloudUserDetails caller) {

        UserProject project = projectService.findById(projectId);
        projectService.assertOwnerOrAdmin(project, caller);

        log.info("Undeploying all containers for project '{}'", project.getName());

        deploymentService.undeployProject(projectId);

        log.info("Project '{}' undeploy completed.", project.getName());

        auditService.userAction(caller, AuditEvent.AuditAction.PROJECT_UNDEPLOYED,
                AuditEvent.TargetType.PROJECT, project.getName(), project,
                "Project undeployed");
    }

    /**
     * Stops/removes all running containers of the project,
     * then deletes the image and project records.
     */
    //could be turned into a soft delete later
    public void deleteProject(Long projectId, BicloudUserDetails caller) {

        UserProject project = projectService.findById(projectId);
        projectService.assertOwnerOrAdmin(project, caller);

        log.info("Deleting project '{}' (id={}): stopping all containers first", project.getName(), projectId);

        // 1. stop all running containers on the workers (HTTP, outside any transaction)
        deploymentService.undeployProject(projectId);

        // audit: record BEFORE deleting (entity and owner references are gone afterwards)
        auditService.userAction(caller, AuditEvent.AuditAction.PROJECT_DELETED,
                AuditEvent.TargetType.PROJECT, project.getName(), project,
                "Project deleted");

        // 2-4. record deletions are atomic
        transactionTemplate.executeWithoutResult(tx -> {
            List<ContainerInstance> allInstances = containerInstanceRepository.findAllByProjectId(projectId);
            containerInstanceRepository.deleteAll(allInstances);

            // image records (env var table goes with cascade)
            List<ProjectImage> images = projectImageRepository.findByProject_Id(projectId);
            projectImageRepository.deleteAll(images);

            userProjectRepository.deleteById(projectId);
        });

        log.info("Project '{}' (id={}) deleted successfully.", project.getName(), projectId);
    }

    /**
     * Stops/removes the running containers of an image, then deletes the image record.
     */
    public void deleteProjectImage(Long imageId, BicloudUserDetails caller) {

        ProjectImage image = projectImageService.findByIdWithProject(imageId);
        projectService.assertOwnerOrAdmin(image.getProject(), caller);

        log.info("Deleting image '{}' (id={}) from project '{}'",
                image.getServiceName(), imageId, image.getProject().getName());

        // 1. stop running containers on the workers (HTTP, outside any transaction)
        List<ContainerInstance> running = containerInstanceRepository
                .findByProjectImageAndStatus(image, ContainerInstance.InstanceStatus.RUNNING);
        for (ContainerInstance instance : running) {
            try {
                deploymentService.stopAndRemove(instance);
            } catch (Exception e) {
                log.warn("Could not stop container {} while deleting image: {}",
                        instance.getDockerContainerId(), e.getMessage());
            }
        }

        // audit: before deleting
        auditService.userAction(caller, AuditEvent.AuditAction.SERVICE_DELETED,
                AuditEvent.TargetType.SERVICE, image.getServiceName(), image.getProject(),
                "Service deleted");

        // 2-3. record deletions are atomic
        transactionTemplate.executeWithoutResult(tx -> {
            List<ContainerInstance> allInstances = containerInstanceRepository.findByProjectImage(image);
            containerInstanceRepository.deleteAll(allInstances);

            // the image itself (env var table goes with cascade)
            projectImageRepository.deleteById(imageId);
        });

        log.info("ProjectImage '{}' (id={}) deleted successfully.", image.getServiceName(), imageId);
    }

    /**
     * Updates the service configuration (image, port, resource limits, env).
     * Running containers still carry the old configuration, so they are all
     * stopped and redeployed with the new one.
     */
    public void updateProjectImage(Long imageId, UpdateProjectImageDto dto, BicloudUserDetails caller) {

        ProjectImage image = projectImageService.findByIdWithProject(imageId);
        projectService.assertOwnerOrAdmin(image.getProject(), caller);

        log.info("Updating image '{}' (id={}) in project '{}'",
                image.getServiceName(), imageId, image.getProject().getName());

        // config mutation is atomic; the entity must be managed while the
        // @ElementCollection map is mutated in place
        ProjectImage updated = transactionTemplate.execute(tx -> {
            ProjectImage managed = projectImageService.findByIdWithProject(imageId);

            managed.setImageName(dto.getImageName());
            managed.setContainerPort(dto.getContainerPort());
            managed.setMemoryLimitMb(dto.getMemoryLimitMb());
            managed.setCpuLimit(dto.getCpuLimit());

            // @ElementCollection: mutate the managed map in place, don't replace the reference
            if (managed.getEnvironmentVariables() == null) {
                managed.setEnvironmentVariables(new HashMap<>());
            }
            managed.getEnvironmentVariables().clear();
            if (dto.getEnvironmentVariables() != null) {
                managed.getEnvironmentVariables().putAll(dto.getEnvironmentVariables());
            }

            // config changed; the old cooldown no longer means anything
            managed.setConsecutiveDeployFailures(0);
            managed.setLastDeployFailureAt(null);
            // an update redeploys below, so the service is live again
            managed.setStoppedByUser(false);
            return projectImageRepository.save(managed);
        });

        // stop containers running with the old configuration (HTTP, outside any transaction)
        List<ContainerInstance> running = containerInstanceRepository
                .findByProjectImageAndStatus(updated, ContainerInstance.InstanceStatus.RUNNING);
        for (ContainerInstance instance : running) {
            try {
                deploymentService.stopAndRemove(instance);
            } catch (Exception e) {
                log.warn("Could not stop container {} while updating image: {}",
                        instance.getDockerContainerId(), e.getMessage());
            }
        }

        // bring up the desired replica count with the new configuration
        if (updated.getDesiredReplicas() > 0) {
            deploymentService.deployAsync(imageId);
        }

        log.info("ProjectImage '{}' (id={}) updated successfully.", image.getServiceName(), imageId);

        auditService.userAction(caller, AuditEvent.AuditAction.SERVICE_UPDATED,
                AuditEvent.TargetType.SERVICE, image.getServiceName(), image.getProject(),
                "Service updated (image=" + image.getImageName() + "), containers recreated");
    }

    public void scale(Long imageId, int newReplicas, BicloudUserDetails caller) {

        ProjectImage image = projectImageService.findByIdWithProject(imageId);
        projectService.assertOwnerOrAdmin(image.getProject(), caller);

        log.info("Scaling service '{}' in project '{}' to {} replicas",
                image.getServiceName(), image.getProject().getName(), newReplicas);

        int oldReplicas = image.getDesiredReplicas();

        // persist the new desired state FIRST: if the worker calls fail,
        // self-healing converges to it instead of the stale count
        image.setDesiredReplicas(newReplicas);
        image.setStoppedByUser(false);
        projectImageRepository.save(image);

        deploymentService.scaleAsync(imageId, newReplicas);

        log.info("Service '{}' scaled to {} replicas.", image.getServiceName(), newReplicas);

        auditService.userAction(caller, AuditEvent.AuditAction.SERVICE_SCALED,
                AuditEvent.TargetType.SERVICE, image.getServiceName(), image.getProject(),
                "Scaled: " + oldReplicas + " -> " + newReplicas + " replicas");
    }
}
