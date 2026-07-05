package com.bic.cloud.controlplane.controller;

import com.bic.cloud.controlplane.dto.WorkerNodeDetailResponse;
import com.bic.cloud.controlplane.security.BicloudUserDetails;
import com.bic.cloud.controlplane.service.WorkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Worker listing endpoints for the frontend (JWT).
 */
@RestController
@RequestMapping("/workers")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class WorkerViewController {

    private final WorkerService workerService;

    @GetMapping
    public ResponseEntity<List<WorkerNodeDetailResponse>> listWorkers() {
        return ResponseEntity.ok(workerService.listAllWorkers());
    }

    @GetMapping("/{workerId}")
    public ResponseEntity<WorkerNodeDetailResponse> getWorker(@PathVariable UUID workerId) {
        return ResponseEntity.ok(workerService.getWorkerDetail(workerId));
    }

    /**
     * Maintenance mode (drain): puts the worker in MAINTENANCE - the scheduler
     * assigns it no new containers, existing ones keep running.
     * enabled=false lifts maintenance (ACTIVE/OVERLOADED is recomputed on the
     * first heartbeat).
     */
    @PutMapping("/{workerId}/maintenance")
    public ResponseEntity<WorkerNodeDetailResponse> setMaintenance(
            @PathVariable UUID workerId,
            @RequestParam boolean enabled,
            @AuthenticationPrincipal BicloudUserDetails caller) {
        return ResponseEntity.ok(
                workerService.setMaintenance(workerId, enabled,
                        caller != null ? caller.getUsername() : null));
    }
}
