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
import java.util.Map;
import java.util.Objects;

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
    private final GatewayNotificationService gatewayNotificationService;

    public void deployProject(Long projectId, BicloudUserDetails caller) {

        UserProject project = projectService.findById(projectId);
        projectService.assertOwnerOrAdmin(project, caller);

        List<ProjectImage> images = projectImageRepository.findByProject_Id(projectId);

        if (images.isEmpty()) {
            throw new NoImagesConfiguredException(project.getName());
        }

        log.info("Deploying project '{}': {} image(s) queued",
                project.getName(), images.size());

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

    public void deleteProject(Long projectId, BicloudUserDetails caller) {

        UserProject project = projectService.findById(projectId);
        projectService.assertOwnerOrAdmin(project, caller);

        log.info("Deleting project '{}' (id={}): stopping all containers first", project.getName(), projectId);

        deploymentService.undeployProject(projectId);

        auditService.userAction(caller, AuditEvent.AuditAction.PROJECT_DELETED,
                AuditEvent.TargetType.PROJECT, project.getName(), project,
                "Project deleted");

        transactionTemplate.executeWithoutResult(tx -> {
            List<ContainerInstance> allInstances = containerInstanceRepository.findAllByProjectId(projectId);
            containerInstanceRepository.deleteAll(allInstances);

            List<ProjectImage> images = projectImageRepository.findByProject_Id(projectId);
            projectImageRepository.deleteAll(images);

            userProjectRepository.deleteById(projectId);
        });

        log.info("Project '{}' (id={}) deleted successfully.", project.getName(), projectId);
    }

    public void deleteProjectImage(Long imageId, BicloudUserDetails caller) {

        ProjectImage image = projectImageService.findByIdWithProject(imageId);
        projectService.assertOwnerOrAdmin(image.getProject(), caller);

        log.info("Deleting image '{}' (id={}) from project '{}'",
                image.getServiceName(), imageId, image.getProject().getName());

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

        auditService.userAction(caller, AuditEvent.AuditAction.SERVICE_DELETED,
                AuditEvent.TargetType.SERVICE, image.getServiceName(), image.getProject(),
                "Service deleted");

        transactionTemplate.executeWithoutResult(tx -> {
            List<ContainerInstance> allInstances = containerInstanceRepository.findByProjectImage(image);
            containerInstanceRepository.deleteAll(allInstances);

            projectImageRepository.deleteById(imageId);
        });

        log.info("ProjectImage '{}' (id={}) deleted successfully.", image.getServiceName(), imageId);
    }

    public void updateProjectImage(Long imageId, UpdateProjectImageDto dto, BicloudUserDetails caller) {

        ProjectImage image = projectImageService.findByIdWithProject(imageId);
        projectService.assertOwnerOrAdmin(image.getProject(), caller);
        boolean egressChanged = dto.isAllowInternet() != image.isAllowInternet();
        boolean exposureChanged = dto.isExposeExternally() != image.isExposeExternally();
        if (egressChanged) {
            ProjectImageService.assertCanSetAllowInternet(true, caller);
        }

        log.info("Updating image '{}' (id={}) in project '{}'",
                image.getServiceName(), imageId, image.getProject().getName());

        boolean[] runtimeConfigChanged = new boolean[1];

        ProjectImage updated = transactionTemplate.execute(tx -> {
            ProjectImage managed = projectImageService.findByIdWithProject(imageId);
            runtimeConfigChanged[0] = hasRuntimeConfigChanged(managed, dto);

            managed.setImageName(dto.getImageName());
            managed.setContainerPort(dto.getContainerPort());
            managed.setMemoryLimitMb(dto.getMemoryLimitMb());
            managed.setCpuLimit(dto.getCpuLimit());
            managed.setAllowInternet(dto.isAllowInternet());
            managed.setExposeExternally(dto.isExposeExternally());
            applyAutoscalingPolicy(managed, dto);

            if (managed.getEnvironmentVariables() == null) {
                managed.setEnvironmentVariables(new HashMap<>());
            }
            managed.getEnvironmentVariables().clear();
            if (dto.getEnvironmentVariables() != null) {
                managed.getEnvironmentVariables().putAll(dto.getEnvironmentVariables());
            }

            if (runtimeConfigChanged[0]) {
                managed.setConsecutiveDeployFailures(0);
                managed.setLastDeployFailureAt(null);
                managed.setStoppedByUser(false);
            }
            return projectImageRepository.save(managed);
        });

        if (runtimeConfigChanged[0]) {
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

            if (updated.getDesiredReplicas() > 0) {
                deploymentService.deployAsync(imageId);
            }
        } else if (exposureChanged) {
            gatewayNotificationService.resyncAll();
        }

        log.info("ProjectImage '{}' (id={}) updated successfully.", image.getServiceName(), imageId);

        auditService.userAction(caller, AuditEvent.AuditAction.SERVICE_UPDATED,
                AuditEvent.TargetType.SERVICE, image.getServiceName(), image.getProject(),
                "Service updated (image=" + updated.getImageName() + ")"
                        + (runtimeConfigChanged[0]
                            ? ", containers recreated"
                            : (exposureChanged ? ", gateway routes refreshed" : ", no runtime changes"))
                        + (egressChanged
                            ? " - internet egress " + (dto.isAllowInternet() ? "ENABLED" : "disabled") + " by admin"
                            : "")
                        + (exposureChanged
                            ? " - external gateway exposure " + (dto.isExposeExternally() ? "ENABLED" : "disabled")
                            : ""));
    }

    private boolean hasRuntimeConfigChanged(ProjectImage image, UpdateProjectImageDto dto) {
        return !Objects.equals(dto.getImageName(), image.getImageName())
                || dto.getContainerPort() != image.getContainerPort()
                || !Objects.equals(dto.getMemoryLimitMb(), image.getMemoryLimitMb())
                || !Objects.equals(dto.getCpuLimit(), image.getCpuLimit())
                || dto.isAllowInternet() != image.isAllowInternet()
                || !normalizeEnv(dto.getEnvironmentVariables()).equals(normalizeEnv(image.getEnvironmentVariables()));
    }

    private Map<String, String> normalizeEnv(Map<String, String> env) {
        return env == null ? Map.of() : env;
    }

    private void applyAutoscalingPolicy(ProjectImage image, UpdateProjectImageDto dto) {
        boolean autoscalingEnabled = dto.getAutoscalingEnabled() != null
                ? dto.getAutoscalingEnabled()
                : image.isAutoscalingEnabled();

        int minReplicas = dto.getMinReplicas() != null ? dto.getMinReplicas() : image.getMinReplicas();
        int maxReplicas = dto.getMaxReplicas() != null ? dto.getMaxReplicas() : image.getMaxReplicas();
        int targetCpuPercent = dto.getTargetCpuPercent() != null
                ? dto.getTargetCpuPercent()
                : image.getTargetCpuPercent();
        int scaleDownCpuPercent = dto.getScaleDownCpuPercent() != null
                ? dto.getScaleDownCpuPercent()
                : image.getScaleDownCpuPercent();
        int scaleUpCooldownSeconds = dto.getScaleUpCooldownSeconds() != null
                ? dto.getScaleUpCooldownSeconds()
                : image.getScaleUpCooldownSeconds();
        int scaleDownCooldownSeconds = dto.getScaleDownCooldownSeconds() != null
                ? dto.getScaleDownCooldownSeconds()
                : image.getScaleDownCooldownSeconds();

        ProjectImageService.assertAutoscalingPolicy(
                autoscalingEnabled,
                minReplicas,
                maxReplicas,
                targetCpuPercent,
                scaleDownCpuPercent);

        image.setAutoscalingEnabled(autoscalingEnabled);
        image.setMinReplicas(minReplicas);
        image.setMaxReplicas(maxReplicas);
        image.setTargetCpuPercent(targetCpuPercent);
        image.setScaleDownCpuPercent(scaleDownCpuPercent);
        image.setScaleUpCooldownSeconds(scaleUpCooldownSeconds);
        image.setScaleDownCooldownSeconds(scaleDownCooldownSeconds);

        if (autoscalingEnabled) {
            int boundedDesired = Math.max(minReplicas, Math.min(maxReplicas, image.getDesiredReplicas()));
            image.setDesiredReplicas(boundedDesired);
        }
    }

    public void scale(Long imageId, int newReplicas, BicloudUserDetails caller) {

        ProjectImage image = projectImageService.findByIdWithProject(imageId);
        projectService.assertOwnerOrAdmin(image.getProject(), caller);

        log.info("Scaling service '{}' in project '{}' to {} replicas",
                image.getServiceName(), image.getProject().getName(), newReplicas);

        int oldReplicas = image.getDesiredReplicas();

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
