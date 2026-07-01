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
    private final AuditService auditService;

    // sort fields allowed in search queries - unknown fields fall back to the default
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("createdAt", "status", "workerName");
    private static final String DEFAULT_SORT_FIELD = "createdAt";
    private static final int MAX_PAGE_SIZE = 200;

    // Listing & Search

    // returns all containers of a project (no paging)
    @Transactional(readOnly = true)
    public List<ContainerInstanceDetailResponse> listByProject(Long projectId, BicloudUserDetails caller) {
        UserProject project = projectService.findById(projectId);
        projectService.assertOwnerOrAdmin(project, caller);

        return containerInstanceRepository.findAllByProjectId(projectId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // filtered + paged container search - used by the frontend container table
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

        // convert a CSV like "RUNNING,FAILED" into an enum list
        List<ContainerInstance.InstanceStatus> statuses = parseStatuses(statusCsv);
        boolean hasStatusFilter = !statuses.isEmpty();

        // treat blank strings as null
        String normalizedSearch  = (search == null      || search.isBlank())      ? null : search.trim();
        String normalizedService = (serviceName == null || serviceName.isBlank()) ? null : serviceName;

        Pageable pageable = buildPageable(page, size, sortBy, sortDir);

        // filtered page query
        Page<ContainerInstance> resultPage = containerInstanceRepository.searchByProject(
                projectId, normalizedService, hasStatusFilter, statuses, normalizedSearch, pageable);

        // status counts - computed without filters (for the UI badges)
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

    // Metrics

    /**
     * Latest samples + short history for the RUNNING containers of a project
     * (optionally a single service). The frontend polls this every 5s for live cards.
     */
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

    // sends a stop command to the worker; marks STOPPED in the DB even if the worker is unreachable
    @Transactional
    public void stopContainer(UUID instanceId, BicloudUserDetails caller) {
        ContainerInstance instance = findInstanceOrThrow(instanceId);
        projectService.assertOwnerOrAdmin(instance.getProjectImage().getProject(), caller);

        try {
            log.info("Stopping container {} on worker {}",
                    instance.getDockerContainerId(), instance.getWorkerNode().getWorkerName());
            workerHttpClient.stopContainer(instance.getWorkerNode(), instance.getDockerContainerId());
            log.info("Container {} stopped successfully", instance.getDockerContainerId());
        } catch (Exception e) {
            log.warn("Worker could not stop container {} (may already be stopped/gone): {}",
                    instance.getDockerContainerId(), e.getMessage());
        } finally {
            // update the DB even if the worker is unreachable
            instance.setStatus(ContainerInstance.InstanceStatus.STOPPED);
            containerInstanceRepository.save(instance);
        }

        auditService.userAction(caller, AuditEvent.AuditAction.CONTAINER_STOPPED,
                AuditEvent.TargetType.CONTAINER, instance.getProjectImage().getServiceName(),
                instance.getProjectImage().getProject(),
                "Container stopped (" + shortId(instance) + " @ " + instance.getWorkerNode().getWorkerName() + ")");
    }

    // sends stop + remove to the worker; marks STOPPED in the DB even if the worker is unreachable
    @Transactional
    public void removeContainer(UUID instanceId, BicloudUserDetails caller) {
        ContainerInstance instance = findInstanceOrThrow(instanceId);
        projectService.assertOwnerOrAdmin(instance.getProjectImage().getProject(), caller);

        try {
            log.info("Removing container {} on worker {}",
                    instance.getDockerContainerId(), instance.getWorkerNode().getWorkerName());
            workerHttpClient.stopAndRemoveContainer(instance.getWorkerNode(), instance.getDockerContainerId());
            log.info("Container {} removed successfully", instance.getDockerContainerId());
        } catch (Exception e) {
            log.warn("Worker could not stop/remove container {} (may already be gone): {}",
                    instance.getDockerContainerId(), e.getMessage());
        } finally {
            instance.setStatus(ContainerInstance.InstanceStatus.STOPPED);
            containerInstanceRepository.save(instance);
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

    // fetches container logs from the worker
    @Transactional(readOnly = true)
    public String getContainerLogs(UUID instanceId, int tailLines, BicloudUserDetails caller) {
        ContainerInstance instance = findInstanceOrThrow(instanceId);
        projectService.assertOwnerOrAdmin(instance.getProjectImage().getProject(), caller);

        log.debug("Fetching logs for container {} (tail={})", instance.getDockerContainerId(), tailLines);
        return workerHttpClient.getContainerLogs(
                instance.getWorkerNode(), instance.getDockerContainerId(), tailLines);
    }

    // Helpers

    // "RUNNING,FAILED" -> [RUNNING, FAILED] - unknown values are ignored
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

    // turns page/sort params into a safe Pageable
    private Pageable buildPageable(int page, int size, String sortBy, String sortDir) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        // fall back to the default if the field is not whitelisted
        String safeSortBy = (sortBy != null && ALLOWED_SORT_FIELDS.contains(sortBy)) ? sortBy : DEFAULT_SORT_FIELD;
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;

        // workerName lives on the joined entity - resolved via JPQL path
        String sortPath = "workerName".equals(safeSortBy) ? "workerNode.workerName" : safeSortBy;

        return PageRequest.of(safePage, safeSize, Sort.by(direction, sortPath));
    }

    // container count per status - unfiltered, for the UI badges
    private Map<String, Long> computeStatusCounts(Long projectId, String serviceName, String search) {
        Map<String, Long> counts = new HashMap<>();
        // pre-fill every status with 0 so none is missing
        for (ContainerInstance.InstanceStatus s : ContainerInstance.InstanceStatus.values()) {
            counts.put(s.name(), 0L);
        }
        // overwrite with the counts from the DB
        for (Object[] row : containerInstanceRepository.countByStatusForProject(projectId, serviceName, search)) {
            ContainerInstance.InstanceStatus status = (ContainerInstance.InstanceStatus) row[0];
            Long count = (Long) row[1];
            counts.put(status.name(), count);
        }
        return counts;
    }

    // find container by id or throw 404
    private ContainerInstance findInstanceOrThrow(UUID instanceId) {
        return containerInstanceRepository.findById(instanceId)
                .orElseThrow(() -> new ContainerInstanceNotFoundException(instanceId));
    }

    // maps a ContainerInstance entity to the DTO sent to the frontend
    private ContainerInstanceDetailResponse toResponse(ContainerInstance ci) {
        WorkerState state = workerStateRepository.findById(ci.getWorkerNode().getId()).orElse(null);

        String workerIp     = state != null ? state.getIpAddress()           : null;
        String workerStatus = state != null && state.getStatus() != null
                              ? state.getStatus().name() : null;

        String serviceName = ci.getProjectImage().getServiceName();
        String projectName = ci.getProjectImage().getProject().getName();

        // external access address - gateway Host header format
        String gatewayUrl = "http://" + serviceName + "." + projectName + ".bicloud.local";

        // latest resource sample (if any) - for the CPU/MEM columns in the container table
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
