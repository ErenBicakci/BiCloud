package com.bic.cloud.bicloud_gateway.controller;

import com.bic.cloud.bicloud_gateway.dto.DeregisterInstanceRequest;
import com.bic.cloud.bicloud_gateway.dto.RegisterInstanceRequest;
import com.bic.cloud.bicloud_gateway.dto.RouteStatusResponse;
import com.bic.cloud.bicloud_gateway.registry.RouteRegistry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
Gateway route management API.
Called by the control plane. End users cannot reach these
 * endpoints directly.
 */
@RestController
@RequestMapping("/gateway")
@RequiredArgsConstructor
@Slf4j
public class GatewayManagementController {

    private final RouteRegistry registry;

    @PostMapping("/register")
    public Mono<ResponseEntity<Map<String, String>>> register(
            @Valid @RequestBody RegisterInstanceRequest request) {

        registry.register(
                request.getProjectName(),
                request.getServiceName(),
                request.getInstanceIp(),
                request.getPort(),
                request.getInstanceId()
        );

        String routeHost = request.getServiceName() + "."
                + request.getProjectName() + ".bicloud.local";

        log.info("Register | route={} | target={}:{}",
                routeHost, request.getInstanceIp(), request.getPort());

        return Mono.just(ResponseEntity
                .status(HttpStatus.CREATED)
                .body(Map.of(
                        "status",  "registered",
                        "route",   routeHost,
                        "target",  request.getInstanceIp() + ":" + request.getPort()
                )));
    }

    @DeleteMapping("/deregister")
    public Mono<ResponseEntity<Map<String, String>>> deregister(
            @Valid @RequestBody DeregisterInstanceRequest request) {

        boolean removed = registry.deregister(
                request.getProjectName(),
                request.getServiceName(),
                request.getInstanceIp(),
                request.getPort()
        );

        if (!removed) {
            return Mono.just(ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "status",  "not_found",
                            "message", "The given instance is not registered or was already removed."
                    )));
        }

        log.info("Deregister | route={}.{} | target={}:{}",
                request.getServiceName(), request.getProjectName(),
                request.getInstanceIp(), request.getPort());

        return Mono.just(ResponseEntity.ok(Map.of(
                "status",   "deregistered",
                "instance", request.getInstanceIp() + ":" + request.getPort()
        )));
    }


    @GetMapping("/routes")
    public Mono<ResponseEntity<RouteStatusResponse>> listRoutes() {
        return Mono.just(ResponseEntity.ok(
                RouteStatusResponse.builder()
                        .totalRoutes(registry.routeCount())
                        .totalInstances(registry.instanceCount())
                        .routes(registry.snapshot())
                        .build()
        ));
    }


    // Simple health check endpoint. x-api-key is not required
    @GetMapping("/health")
    public Mono<ResponseEntity<Map<String, Object>>> health() {
        return Mono.just(ResponseEntity.ok(Map.of(
                "status",    "UP",
                "routes",    registry.routeCount(),
                "instances", registry.instanceCount()
        )));
    }
}
