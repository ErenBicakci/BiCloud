package com.bic.cloud.controlplane.controller;

import com.bic.cloud.controlplane.dto.ContainerInstanceDetailResponse;
import com.bic.cloud.controlplane.dto.ContainerMetricsResponse;
import com.bic.cloud.controlplane.dto.PagedContainerResponse;
import com.bic.cloud.controlplane.security.BicloudUserDetails;
import com.bic.cloud.controlplane.service.ContainerManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/containers")
public class ContainerManagementController {

    private final ContainerManagementService containerManagementService;

    @GetMapping("/project/{projectId}")
    public ResponseEntity<List<ContainerInstanceDetailResponse>> listByProject(
            @PathVariable Long projectId,
            @AuthenticationPrincipal BicloudUserDetails caller) {

        return ResponseEntity.ok(containerManagementService.listByProject(projectId, caller));
    }

    @GetMapping("/project/{projectId}/search")
    public ResponseEntity<PagedContainerResponse> searchByProject(
            @PathVariable Long projectId,
            @RequestParam(required = false) String serviceName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "createdAt") String sortBy,
            @RequestParam(required = false, defaultValue = "desc") String sortDir,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "25") int size,
            @AuthenticationPrincipal BicloudUserDetails caller) {

        PagedContainerResponse response = containerManagementService.searchByProject(
                projectId, serviceName, status, search, sortBy, sortDir, page, size, caller);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/project/{projectId}/metrics")
    public ResponseEntity<List<ContainerMetricsResponse>> getMetricsByProject(
            @PathVariable Long projectId,
            @RequestParam(required = false) String serviceName,
            @AuthenticationPrincipal BicloudUserDetails caller) {

        return ResponseEntity.ok(
                containerManagementService.getMetricsByProject(projectId, serviceName, caller));
    }

    @PostMapping("/{instanceId}/stop")
    public ResponseEntity<Void> stopContainer(
            @PathVariable UUID instanceId,
            @AuthenticationPrincipal BicloudUserDetails caller) {

        containerManagementService.stopContainer(instanceId, caller);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{instanceId}")
    public ResponseEntity<Void> removeContainer(
            @PathVariable UUID instanceId,
            @AuthenticationPrincipal BicloudUserDetails caller) {

        containerManagementService.removeContainer(instanceId, caller);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{instanceId}/logs")
    public ResponseEntity<Map<String, String>> getContainerLogs(
            @PathVariable UUID instanceId,
            @RequestParam(defaultValue = "100") int tail,
            @AuthenticationPrincipal BicloudUserDetails caller) {

        String logs = containerManagementService.getContainerLogs(instanceId, tail, caller);
        return ResponseEntity.ok(Map.of("instanceId", instanceId.toString(), "logs", logs));
    }
}
