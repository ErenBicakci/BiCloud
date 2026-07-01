package com.bic.cloud.controlplane.controller;

import com.bic.cloud.controlplane.service.GatewayNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/gateway")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class GatewayAdminController {

    private final GatewayNotificationService gatewayNotificationService;

    /**
     * Re-registers every RUNNING container in the DB with the gateway.
     * Happens automatically on CP restart; this is the manual trigger.
     * @return number of re-registered containers
     */
    @PostMapping("/resync")
    public ResponseEntity<Map<String, Object>> resync() {
        int count = gatewayNotificationService.resyncAll();
        return ResponseEntity.ok(Map.of(
                "status",     "ok",
                "registered", count,
                "message",    count + " container(s) re-registered with the gateway."
        ));
    }
}
