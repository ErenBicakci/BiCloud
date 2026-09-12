package com.bic.cloud.controlplane.controller;

import com.bic.cloud.controlplane.dto.*;
import com.bic.cloud.controlplane.service.ContainerMetricsService;
import com.bic.cloud.controlplane.service.ContainerReconciliationService;
import com.bic.cloud.controlplane.service.GatewayNotificationService;
import com.bic.cloud.controlplane.service.ServiceDiscoveryService;
import com.bic.cloud.controlplane.service.WorkerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workers")
public class WorkerNodeController {

    private final WorkerService workerService;
    private final ServiceDiscoveryService serviceDiscoveryService;
    private final ContainerReconciliationService reconciliationService;
    private final ContainerMetricsService containerMetricsService;
    private final GatewayNotificationService gatewayNotificationService;

    @PostMapping("/register")
    public ResponseEntity<WorkerRegisterResponse> registerWorkerNode(
            @Valid @RequestBody WorkerRegisterRequest request,
            HttpServletRequest servletRequest) {

        String clientIp = servletRequest.getRemoteAddr();
        WorkerRegisterResponse response = workerService.register(request, clientIp);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(
            @Valid @RequestBody WorkerHeartbeatRequest request) {

        workerService.handleHeartbeat(request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/deregister")
    public ResponseEntity<Void> deregister(
            @Valid @RequestBody WorkerHeartbeatRequest request) {

        workerService.deregister(request.getWorkerId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/container-status")
    public ResponseEntity<Void> containerStatusUpdate(
            @RequestBody ContainerStatusUpdateRequest request) {

        workerService.handleContainerStatusUpdate(request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/container-snapshot")
    public ResponseEntity<Void> containerSnapshot(
            @RequestBody ContainerSnapshotRequest request) {

        reconciliationService.reconcile(
                request.getWorkerId(),
                request.getRunningContainerIds()
        );
        containerMetricsService.record(request.getContainerStats());
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public ResponseEntity<List<WorkerNodeDetailResponse>> listWorkers() {
        return ResponseEntity.ok(workerService.listAllWorkers());
    }

    @GetMapping("/{workerId}")
    public ResponseEntity<WorkerNodeDetailResponse> getWorker(
            @PathVariable UUID workerId) {

        return ResponseEntity.ok(workerService.getWorkerDetail(workerId));
    }

    @GetMapping("/discover/{projectName}/{serviceName}")
    public ResponseEntity<java.util.List<ServiceEndpointDto>> discoverForWorker(
            @PathVariable String projectName,
            @PathVariable String serviceName) {

        return ResponseEntity.ok(serviceDiscoveryService.getEndpoints(projectName, serviceName));
    }

    @PostMapping("/gateway-resync")
    public ResponseEntity<java.util.Map<String, Object>> gatewayResync() {
        int count = gatewayNotificationService.resyncAll();
        return ResponseEntity.ok(java.util.Map.of("registered", count));
    }
}