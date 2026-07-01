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
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;

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

    @Transactional
    public void deployProject(Long projectId, BicloudUserDetails caller) {

        UserProject project = projectService.findById(projectId);
        projectService.assertOwnerOrAdmin(project, caller);

        List<ProjectImage> images = projectImageRepository.findByProject_Id(projectId);

        if (images.isEmpty()) {
            throw new NoImagesConfiguredException(project.getName());
        }

        log.info("Deploying project '{}': {} image(s) to deploy",
                project.getName(), images.size());

        for (ProjectImage image : images) {
            deploymentService.deploy(image);
        }

        log.info("Project '{}' deployment completed.", project.getName());

        auditService.userAction(caller, AuditEvent.AuditAction.PROJECT_DEPLOYED,
                AuditEvent.TargetType.PROJECT, project.getName(), project,
                "Deployed " + images.size() + " service(s)");
    }

    @Transactional
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
    @Transactional
    public void deleteProject(Long projectId, BicloudUserDetails caller) {

        UserProject project = projectService.findById(projectId);
        projectService.assertOwnerOrAdmin(project, caller);

        log.info("Deleting project '{}' (id={}): stopping all containers first", project.getName(), projectId);

        // 1. stop all running containers on the workers
        deploymentService.undeployProject(projectId);

        // 2. delete container instance records
        List<ContainerInstance> allInstances = containerInstanceRepository.findAllByProjectId(projectId);
        containerInstanceRepository.deleteAll(allInstances);

        // 3. delete image records (env var table goes with cascade)
        List<ProjectImage> images = projectImageRepository.findByProject_Id(projectId);
        projectImageRepository.deleteAll(images);

        // audit: record BEFORE deleting (entity and owner references are gone afterwards)
        auditService.userAction(caller, AuditEvent.AuditAction.PROJECT_DELETED,
                AuditEvent.TargetType.PROJECT, project.getName(), project,
                "Project deleted along with " + images.size() + " service(s)");

        // 4. delete the project
        userProjectRepository.delete(project);

        log.info("Project '{}' (id={}) deleted successfully.", project.getName(), projectId);
    }

    /**
     * Stops/removes the running containers of an image, then deletes the image record.
     */
    @Transactional
    public void deleteProjectImage(Long imageId, BicloudUserDetails caller) {

        ProjectImage image = projectImageService.findByIdWithProject(imageId);
        projectService.assertOwnerOrAdmin(image.getProject(), caller);

        log.info("Deleting image '{}' (id={}) from project '{}'",
                image.getServiceName(), imageId, image.getProject().getName());

        // 1. stop running containers on the workers
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

        // 2. delete all instance records of this image
        List<ContainerInstance> allInstances = containerInstanceRepository.findByProjectImage(image);
        containerInstanceRepository.deleteAll(allInstances);

        // audit: before deleting
        auditService.userAction(caller, AuditEvent.AuditAction.SERVICE_DELETED,
                AuditEvent.TargetType.SERVICE, image.getServiceName(), image.getProject(),
                "Service deleted");

        // 3. delete the image (env var table goes with cascade)
        projectImageRepository.delete(image);

        log.info("ProjectImage '{}' (id={}) deleted successfully.", image.getServiceName(), imageId);
    }

    /**
     * Updates the service configuration (image, port, resource limits, env).
     * Running containers still carry the old configuration, so they are all
     * stopped and redeployed with the new one.
     */
    @Transactional
    public void updateProjectImage(Long imageId, UpdateProjectImageDto dto, BicloudUserDetails caller) {

        ProjectImage image = projectImageService.findByIdWithProject(imageId);
        projectService.assertOwnerOrAdmin(image.getProject(), caller);

        log.info("Updating image '{}' (id={}) in project '{}'",
                image.getServiceName(), imageId, image.getProject().getName());

        image.setImageName(dto.getImageName());
        image.setContainerPort(dto.getContainerPort());
        image.setMemoryLimitMb(dto.getMemoryLimitMb());
        image.setCpuLimit(dto.getCpuLimit());

        // @ElementCollection: mutate the managed map in place, don't replace the reference
        if (image.getEnvironmentVariables() == null) {
            image.setEnvironmentVariables(new HashMap<>());
        }
        image.getEnvironmentVariables().clear();
        if (dto.getEnvironmentVariables() != null) {
            image.getEnvironmentVariables().putAll(dto.getEnvironmentVariables());
        }

        // config changed; the old cooldown no longer means anything
        image.setConsecutiveDeployFailures(0);
        image.setLastDeployFailureAt(null);
        projectImageRepository.save(image);

        // stop containers running with the old configuration
        List<ContainerInstance> running = containerInstanceRepository
                .findByProjectImageAndStatus(image, ContainerInstance.InstanceStatus.RUNNING);
        for (ContainerInstance instance : running) {
            try {
                deploymentService.stopAndRemove(instance);
            } catch (Exception e) {
                log.warn("Could not stop container {} while updating image: {}",
                        instance.getDockerContainerId(), e.getMessage());
            }
        }

        // bring up the desired replica count with the new configuration
        if (image.getDesiredReplicas() > 0) {
            deploymentService.deploy(image);
        }

        log.info("ProjectImage '{}' (id={}) updated successfully.", image.getServiceName(), imageId);

        auditService.userAction(caller, AuditEvent.AuditAction.SERVICE_UPDATED,
                AuditEvent.TargetType.SERVICE, image.getServiceName(), image.getProject(),
                "Service updated (image=" + image.getImageName() + "), containers recreated");
    }

    @Transactional
    public void scale(Long imageId, int newReplicas, BicloudUserDetails caller) {

        ProjectImage image = projectImageService.findByIdWithProject(imageId);
        projectService.assertOwnerOrAdmin(image.getProject(), caller);

        log.info("Scaling service '{}' in project '{}' to {} replicas",
                image.getServiceName(), image.getProject().getName(), newReplicas);

        int oldReplicas = image.getDesiredReplicas();
        deploymentService.scale(image, newReplicas);

        image.setDesiredReplicas(newReplicas);
        projectImageRepository.save(image);

        log.info("Service '{}' scaled to {} replicas.", image.getServiceName(), newReplicas);

        auditService.userAction(caller, AuditEvent.AuditAction.SERVICE_SCALED,
                AuditEvent.TargetType.SERVICE, image.getServiceName(), image.getProject(),
                "Scaled: " + oldReplicas + " -> " + newReplicas + " replicas");
    }
}
