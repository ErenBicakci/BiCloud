package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.dto.CreateProjectDto;
import com.bic.cloud.controlplane.dto.CreateProjectResponse;
import com.bic.cloud.controlplane.dto.ProjectDetailResponse;
import com.bic.cloud.controlplane.exception.ForbiddenException;
import com.bic.cloud.controlplane.exception.NameConflictException;
import com.bic.cloud.controlplane.exception.ProjectNotFoundException;
import com.bic.cloud.controlplane.model.AuditEvent;
import com.bic.cloud.controlplane.model.BicloudUser;
import com.bic.cloud.controlplane.model.ContainerInstance;
import com.bic.cloud.controlplane.model.ProjectImage;
import com.bic.cloud.controlplane.model.UserProject;
import com.bic.cloud.controlplane.repository.BicloudUserRepository;
import com.bic.cloud.controlplane.repository.ContainerInstanceRepository;
import com.bic.cloud.controlplane.repository.ProjectImageRepository;
import com.bic.cloud.controlplane.repository.UserProjectRepository;
import com.bic.cloud.controlplane.security.BicloudUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final UserProjectRepository userProjectRepository;
    private final ProjectImageRepository projectImageRepository;
    private final ContainerInstanceRepository containerInstanceRepository;
    private final BicloudUserRepository userRepository;
    private final AuditService auditService;

    @Transactional
    public CreateProjectResponse createProject(CreateProjectDto dto, BicloudUserDetails caller) {

        BicloudUser owner = userRepository.findById(caller.getId())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        if (userProjectRepository.existsByName(dto.getName())) {
            throw NameConflictException.projectName(dto.getName());
        }

        UserProject userProject = new UserProject();
        userProject.setName(dto.getName());
        userProject.setOwner(owner);

        UserProject saved = userProjectRepository.save(userProject);
        log.info("Project created: id={}, name={}, owner={}", saved.getId(), saved.getName(), owner.getUsername());

        auditService.userAction(caller, AuditEvent.AuditAction.PROJECT_CREATED,
                AuditEvent.TargetType.PROJECT, saved.getName(), saved,
                "Project created");

        return CreateProjectResponse.builder()
                .id(saved.getId())
                .name(saved.getName())
                .ownerUsername(owner.getUsername())
                .createdAt(saved.getCreatedAt())
                .build();
    }

    public UserProject findById(Long id) {
        return userProjectRepository.findById(id)
                .orElseThrow(() -> new ProjectNotFoundException(id));
    }

    public void assertOwnerOrAdmin(UserProject project, BicloudUserDetails caller) {
        boolean isAdmin = caller.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!isAdmin && !project.getOwner().getId().equals(caller.getId())) {
            throw new ForbiddenException("You are not the owner of project: " + project.getName());
        }
    }

    @Transactional(readOnly = true)
    public List<ProjectDetailResponse> listAllProjects(BicloudUserDetails caller) {
        boolean isAdmin = caller.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        List<UserProject> projects = isAdmin
                ? userProjectRepository.findAll()
                : userProjectRepository.findAllByOwner_Id(caller.getId());

        return projects.stream()
                .map(this::toDetailResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ProjectDetailResponse getProjectDetail(Long projectId, BicloudUserDetails caller) {
        UserProject project = findById(projectId);
        assertOwnerOrAdmin(project, caller);
        return toDetailResponse(project);
    }

    private ProjectDetailResponse toDetailResponse(UserProject project) {

        List<ProjectImage> images = projectImageRepository.findByProject_Id(project.getId());

        int totalRunning = 0;

        List<ProjectDetailResponse.ImageSummary> imageSummaries = new ArrayList<>();

        for (ProjectImage img : images) {
            long running = containerInstanceRepository
                    .countByProjectImageAndStatus(img, ContainerInstance.InstanceStatus.RUNNING);
            totalRunning += (int) running;

            imageSummaries.add(ProjectDetailResponse.ImageSummary.builder()
                    .id(img.getId())
                    .serviceName(img.getServiceName())
                    .imageName(img.getImageName())
                    .desiredReplicas(img.getDesiredReplicas())
                    .autoscalingEnabled(img.isAutoscalingEnabled())
                    .minReplicas(img.getMinReplicas())
                    .maxReplicas(img.getMaxReplicas())
                    .targetCpuPercent(img.getTargetCpuPercent())
                    .scaleDownCpuPercent(img.getScaleDownCpuPercent())
                    .scaleUpCooldownSeconds(img.getScaleUpCooldownSeconds())
                    .scaleDownCooldownSeconds(img.getScaleDownCooldownSeconds())
                    .runningReplicas(running)
                    .containerPort(img.getContainerPort())
                    .memoryLimitMb(img.getMemoryLimitMb())
                    .cpuLimit(img.getCpuLimit())
                    .environmentVariables(img.getEnvironmentVariables())
                    .createdAt(img.getCreatedAt())
                    .consecutiveDeployFailures(img.getConsecutiveDeployFailures())
                    .lastDeployFailureAt(img.getLastDeployFailureAt())
                    .allowInternet(img.isAllowInternet())
                    .exposeExternally(img.isExposeExternally())
                    .build());
        }

        return ProjectDetailResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .ownerUsername(project.getOwner() != null ? project.getOwner().getUsername() : null)
                .createdAt(project.getCreatedAt())
                .images(imageSummaries)
                .totalRunningContainers(totalRunning)
                .build();
    }
}
