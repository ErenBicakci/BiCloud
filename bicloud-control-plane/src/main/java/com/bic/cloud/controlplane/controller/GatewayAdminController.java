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
