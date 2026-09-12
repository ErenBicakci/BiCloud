package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.client.WorkerHttpClient;
import com.bic.cloud.controlplane.dto.ContainerInstanceDetailResponse;
import com.bic.cloud.controlplane.dto.ContainerMetricsResponse;
import com.bic.cloud.controlplane.dto.PagedContainerResponse;
import com.bic.cloud.controlplane.model.AuditEvent;
import com.bic.cloud.controlplane.exception.ContainerInstanceNotFoundException;
import com.bic.cloud.controlplane.model.ContainerInstance;
import com.bic.cloud.controlplane.model.UserProject;
import com.bic.cloud.controlplane.model.WorkerState;
import com.bic.cloud.controlplane.repository.ContainerInstanceRepository;
import com.bic.cloud.controlplane.repository.WorkerStateRepository;
import com.bic.cloud.controlplane.security.BicloudUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContainerManagementService {

    private final ContainerInstanceRepository containerInstanceRepository;
    private final WorkerHttpClient workerHttpClient;
    private final ProjectService projectService;
    private final WorkerStateRepository workerStateRepository;
    private final ContainerMetricsService containerMetricsService;
    private final GatewayNotificationService gatewayNotificationService;
    private final AuditService auditService;

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("createdAt", "status", "workerName");
    private static final String DEFAULT_SORT_FIELD = "createdAt";
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MIN_LOG_TAIL_LINES = 1;
    private static final int MAX_LOG_TAIL_LINES = 1000;

    @Transactional(readOnly = true)
    public List<ContainerInstanceDetailResponse> listByProject(Long projectId, BicloudUserDetails caller) {
        UserProject project = projectService.findById(projectId);
        projectService.assertOwnerOrAdmin(project, caller);

        return containerInstanceRepository.findAllByProjectId(projectId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PagedContainerResponse searchByProject(
            Long projectId,
            String serviceName,
            String statusCsv,
            String search,
            String sortBy,
            String sortDir,
            int page,
            int size,
            BicloudUserDetails caller) {

        UserProject project = projectService.findById(projectId);
        projectService.assertOwnerOrAdmin(project, caller);

        List<ContainerInstance.InstanceStatus> statuses = parseStatuses(statusCsv);
        boolean hasStatusFilter = !statuses.isEmpty();

        String normalizedSearch  = (search == null      || search.isBlank())      ? null : search.trim();
        String normalizedService = (serviceName == null || serviceName.isBlank()) ? null : serviceName;

        Pageable pageable = buildPageable(page, size, sortBy, sortDir);

        Page<ContainerInstance> resultPage = containerInstanceRepository.searchByProject(
                projectId, normalizedService, hasStatusFilter, statuses, normalizedSearch, pageable);

        Map<String, Long> statusCounts = computeStatusCounts(projectId, normalizedService, normalizedSearch);

        return PagedContainerResponse.builder()
                .content(resultPage.getContent().stream().map(this::toResponse).collect(Collectors.toList()))
                .page(resultPage.getNumber())
                .size(resultPage.getSize())
                .totalElements(resultPage.getTotalElements())
                .totalPages(resultPage.getTotalPages())
                .statusCounts(statusCounts)
                .build();
    }

    @Transactional(readOnly = true)
    public List<ContainerMetricsResponse> getMetricsByProject(
            Long projectId, String serviceName, BicloudUserDetails caller) {

        UserProject project = projectService.findById(projectId);
        projectService.assertOwnerOrAdmin(project, caller);

        String normalizedService = (serviceName == null || serviceName.isBlank()) ? null : serviceName;

        return containerInstanceRepository.findRunningByProjectId(projectId).stream()
                .filter(ci -> normalizedService == null
                        || normalizedService.equals(ci.getProjectImage().getServiceName()))
                .map(this::toMetricsResponse)
                .collect(Collectors.toList());
    }

    private ContainerMetricsResponse toMetricsResponse(ContainerInstance ci) {
        String dockerId = ci.getDockerContainerId();

        ContainerMetricsService.MetricPoint latest =
                dockerId != null ? containerMetricsService.getLatest(dockerId) : null;

        List<ContainerMetricsResponse.Point> points = dockerId == null ? List.of()
                : containerMetricsService.getHistory(dockerId).stream()
                        .map(p -> ContainerMetricsResponse.Point.builder()
                                .at(p.at())
                                .cpuPercent(p.cpuPercent())
                                .memoryUsedMb(p.memoryUsedMb())
                                .build())
                        .collect(Collectors.toList());

        return ContainerMetricsResponse.builder()
                .instanceId(ci.getId())
                .dockerContainerId(dockerId)
                .serviceName(ci.getProjectImage().getServiceName())
                .workerName(ci.getWorkerNode().getWorkerName())
                .cpuPercent(latest != null ? latest.cpuPercent() : null)
                .memoryUsedMb(latest != null ? latest.memoryUsedMb() : null)
                .memoryLimitMb(latest != null ? latest.memoryLimitMb() : null)
                .updatedAt(latest != null ? latest.at() : null)
                .points(points)
                .build();
    }

    // Container operations

    public void stopContainer(UUID instanceId, BicloudUserDetails caller) {
        ContainerInstance instance = findInstanceOrThrow(instanceId);
        projectService.assertOwnerOrAdmin(instance.getProjectImage().getProject(), caller);

        gatewayNotificationService.deregister(instance);

        instance.setStatus(ContainerInstance.InstanceStatus.STOPPING);
        containerInstanceRepository.save(instance);

        if (instance.getDockerContainerId() == null || instance.getDockerContainerId().isBlank()) {
            instance.setStatus(ContainerInstance.InstanceStatus.STOPPED);
            containerInstanceRepository.save(instance);
            return;
        }

        try {
            log.info("Stopping container {} on worker {}",
                    instance.getDockerContainerId(), instance.getWorkerNode().getWorkerName());
            workerHttpClient.stopContainer(instance.getWorkerNode(), instance.getDockerContainerId());
            log.info("Container {} stopped successfully", instance.getDockerContainerId());
            instance.setStatus(ContainerInstance.InstanceStatus.STOPPED);
            containerInstanceRepository.save(instance);
        } catch (Exception e) {
            log.warn("Worker could not stop container {} (leaving in STOPPING for reconciler): {}",
                    instance.getDockerContainerId(), e.getMessage());
        }

        auditService.userAction(caller, AuditEvent.AuditAction.CONTAINER_STOPPED,
                AuditEvent.TargetType.CONTAINER, instance.getProjectImage().getServiceName(),
                instance.getProjectImage().getProject(),
                "Container stopped (" + shortId(instance) + " @ " + instance.getWorkerNode().getWorkerName() + ")");
    }

    public void removeContainer(UUID instanceId, BicloudUserDetails caller) {
        ContainerInstance instance = findInstanceOrThrow(instanceId);
        projectService.assertOwnerOrAdmin(instance.getProjectImage().getProject(), caller);

        gatewayNotificationService.deregister(instance);

        instance.setStatus(ContainerInstance.InstanceStatus.STOPPING);
        containerInstanceRepository.save(instance);

        if (instance.getDockerContainerId() == null || instance.getDockerContainerId().isBlank()) {
            instance.setStatus(ContainerInstance.InstanceStatus.STOPPED);
            containerInstanceRepository.save(instance);
            return;
        }

        try {
            log.info("Removing container {} on worker {}",
                    instance.getDockerContainerId(), instance.getWorkerNode().getWorkerName());
            workerHttpClient.stopAndRemoveContainer(instance.getWorkerNode(), instance.getDockerContainerId());
            log.info("Container {} removed successfully", instance.getDockerContainerId());
            instance.setStatus(ContainerInstance.InstanceStatus.STOPPED);
            containerInstanceRepository.save(instance);
        } catch (Exception e) {
            log.warn("Worker could not stop/remove container {} (leaving in STOPPING for reconciler): {}",
                    instance.getDockerContainerId(), e.getMessage());
        }

        auditService.userAction(caller, AuditEvent.AuditAction.CONTAINER_REMOVED,
                AuditEvent.TargetType.CONTAINER, instance.getProjectImage().getServiceName(),
                instance.getProjectImage().getProject(),
                "Container removed (" + shortId(instance) + " @ " + instance.getWorkerNode().getWorkerName() + ")");
    }

    private String shortId(ContainerInstance instance) {
        String id = instance.getDockerContainerId();
        return id != null && id.length() > 12 ? id.substring(0, 12) : (id != null ? id : "-");
    }

    @Transactional(readOnly = true)
    public String getContainerLogs(UUID instanceId, int tailLines, BicloudUserDetails caller) {
        ContainerInstance instance = findInstanceOrThrow(instanceId);
        projectService.assertOwnerOrAdmin(instance.getProjectImage().getProject(), caller);

        int safeTailLines = clampLogTail(tailLines);
        log.debug("Fetching logs for container {} (tail={})", instance.getDockerContainerId(), safeTailLines);
        return workerHttpClient.getContainerLogs(
                instance.getWorkerNode(), instance.getDockerContainerId(), safeTailLines);
    }

    private List<ContainerInstance.InstanceStatus> parseStatuses(String statusCsv) {
        if (statusCsv == null || statusCsv.isBlank()) return List.of();
        return Arrays.stream(statusCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.equalsIgnoreCase("ALL"))
                .map(s -> {
                    try {
                        return ContainerInstance.InstanceStatus.valueOf(s.toUpperCase());
                    } catch (IllegalArgumentException ex) {
                        log.warn("Unknown status filter value ignored: '{}'", s);
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    private Pageable buildPageable(int page, int size, String sortBy, String sortDir) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        String safeSortBy = (sortBy != null && ALLOWED_SORT_FIELDS.contains(sortBy)) ? sortBy : DEFAULT_SORT_FIELD;
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;

        String sortPath = "workerName".equals(safeSortBy) ? "workerNode.workerName" : safeSortBy;

        return PageRequest.of(safePage, safeSize, Sort.by(direction, sortPath));
    }

    private Map<String, Long> computeStatusCounts(Long projectId, String serviceName, String search) {
        Map<String, Long> counts = new HashMap<>();
        for (ContainerInstance.InstanceStatus s : ContainerInstance.InstanceStatus.values()) {
            counts.put(s.name(), 0L);
        }
        for (Object[] row : containerInstanceRepository.countByStatusForProject(projectId, serviceName, search)) {
            ContainerInstance.InstanceStatus status = (ContainerInstance.InstanceStatus) row[0];
            Long count = (Long) row[1];
            counts.put(status.name(), count);
        }
        return counts;
    }

    private ContainerInstance findInstanceOrThrow(UUID instanceId) {
        return containerInstanceRepository.findById(instanceId)
                .orElseThrow(() -> new ContainerInstanceNotFoundException(instanceId));
    }

    private int clampLogTail(int tailLines) {
        return Math.min(Math.max(tailLines, MIN_LOG_TAIL_LINES), MAX_LOG_TAIL_LINES);
    }

    private ContainerInstanceDetailResponse toResponse(ContainerInstance ci) {
        WorkerState state = workerStateRepository.findById(ci.getWorkerNode().getId()).orElse(null);

        String workerIp     = state != null ? state.getIpAddress()           : null;
        String workerStatus = state != null && state.getStatus() != null
                              ? state.getStatus().name() : null;

        String serviceName = ci.getProjectImage().getServiceName();
        String projectName = ci.getProjectImage().getProject().getName();

        String gatewayUrl = "http://" + serviceName + "." + projectName + ".bicloud.local";

        ContainerMetricsService.MetricPoint latest = ci.getDockerContainerId() != null
                ? containerMetricsService.getLatest(ci.getDockerContainerId()) : null;

        return ContainerInstanceDetailResponse.builder()
                .cpuPercent(latest != null ? latest.cpuPercent() : null)
                .memoryUsedMb(latest != null ? latest.memoryUsedMb() : null)
                .memoryLimitMb(latest != null ? latest.memoryLimitMb() : null)
                .metricsAt(latest != null ? latest.at() : null)
                .id(ci.getId())
                .dockerContainerId(ci.getDockerContainerId())
                .serviceName(serviceName)
                .projectName(projectName)
                .workerName(ci.getWorkerNode().getWorkerName())
                .workerId(ci.getWorkerNode().getId())
                .workerIp(workerIp)
                .workerStatus(workerStatus)
                .status(ci.getStatus().name())
                .createdAt(ci.getCreatedAt())
                .gatewayUrl(gatewayUrl)
                .build();
    }
}
