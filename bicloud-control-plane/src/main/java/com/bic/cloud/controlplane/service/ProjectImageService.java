package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.dto.CreateProjectImageDto;
import com.bic.cloud.controlplane.dto.ProjectImageResponse;
import com.bic.cloud.controlplane.exception.ProjectImageNotFoundException;
import com.bic.cloud.controlplane.exception.ProjectNotFoundException;
import com.bic.cloud.controlplane.model.AuditEvent;
import com.bic.cloud.controlplane.model.ProjectImage;
import com.bic.cloud.controlplane.model.UserProject;
import com.bic.cloud.controlplane.repository.ProjectImageRepository;
import com.bic.cloud.controlplane.repository.UserProjectRepository;
import com.bic.cloud.controlplane.security.BicloudUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectImageService {

    private final UserProjectRepository userProjectRepository;
    private final ProjectImageRepository projectImageRepository;
    private final ProjectService projectService;
    private final AuditService auditService;

    public ProjectImage findById(Long id) {
        return projectImageRepository
                .findById(id)
                .orElseThrow(() -> new ProjectImageNotFoundException(id));
    }

    public ProjectImage findByIdWithProject(Long id) {
        return projectImageRepository
                .findByIdWithProject(id)
                .orElseThrow(() -> new ProjectImageNotFoundException(id));
    }

    @Transactional
    public ProjectImageResponse createProjectImage(CreateProjectImageDto dto, BicloudUserDetails caller) {

        UserProject project = userProjectRepository
                .findById(dto.getProjectId())
                .orElseThrow(() -> new ProjectNotFoundException(dto.getProjectId()));

        projectService.assertOwnerOrAdmin(project, caller);

        ProjectImage projectImage = ProjectImage.builder()
                .project(project)
                .serviceName(dto.getServiceName())
                .imageName(dto.getImageName())
                .containerPort(dto.getContainerPort())
                .memoryLimitMb(dto.getMemoryLimitMb())
                .cpuLimit(dto.getCpuLimit())
                .environmentVariables(dto.getEnvironmentVariables())
                .desiredReplicas(dto.getDesiredReplicas())
                .build();

        ProjectImage saved = projectImageRepository.save(projectImage);
        log.info("ProjectImage created: id={}, service={}, image={}, project={}",
                saved.getId(), saved.getServiceName(), saved.getImageName(), project.getName());

        auditService.userAction(caller, AuditEvent.AuditAction.SERVICE_CREATED,
                AuditEvent.TargetType.SERVICE, saved.getServiceName(), project,
                "Service added (image=" + saved.getImageName() + ", " + saved.getDesiredReplicas() + " replika)");

        return ProjectImageResponse.builder()
                .id(saved.getId())
                .projectId(project.getId())
                .serviceName(saved.getServiceName())
                .imageName(saved.getImageName())
                .containerPort(saved.getContainerPort())
                .desiredReplicas(saved.getDesiredReplicas())
                .memoryLimitMb(saved.getMemoryLimitMb())
                .cpuLimit(saved.getCpuLimit())
                .build();
    }

    /**
     * Manually clears the self-healing backoff cooldown.
     * The operator calls this after fixing a broken image/env;
     * SelfHealingScheduler puts the service back on the retry list.
     */
    @Transactional
    public void resetDeployFailures(Long imageId, BicloudUserDetails caller) {
        ProjectImage image = findByIdWithProject(imageId);
        projectService.assertOwnerOrAdmin(image.getProject(), caller);

        if (image.getConsecutiveDeployFailures() == 0 && image.getLastDeployFailureAt() == null) {
            log.info("ResetDeployFailures: service '{}' has no failures to reset.", image.getServiceName());
            return;
        }

        log.info("ResetDeployFailures: service '{}' (was {} failures, last={}) -> cleared by {}",
                image.getServiceName(),
                image.getConsecutiveDeployFailures(),
                image.getLastDeployFailureAt(),
                caller.getUsername());

        image.setConsecutiveDeployFailures(0);
        image.setLastDeployFailureAt(null);
        projectImageRepository.save(image);
    }

}