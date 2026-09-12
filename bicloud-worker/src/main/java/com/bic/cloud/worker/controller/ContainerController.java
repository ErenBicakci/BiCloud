package com.bic.cloud.worker.controller;

import com.bic.cloud.worker.docker.DockerContainerService;
import com.bic.cloud.worker.dto.ContainerCreateRequest;
import com.bic.cloud.worker.dto.ContainerCreateResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/containers")
@RequiredArgsConstructor
public class ContainerController {

    private final DockerContainerService dockerContainerService;

    @Value("${bicloud.gateway.alias:bicloud-gateway}")
    private String gatewayAlias;

    @Value("${bicloud.gateway.port:9000}")
    private int gatewayPort;

    @PostMapping("/create")
    public ResponseEntity<ContainerCreateResponse> createContainer(
            @RequestBody ContainerCreateRequest dto) throws InterruptedException {

        // the CP normally injects this itself; kept here as a fallback in case
        // the worker API is called directly.
        Map<String, String> env = new HashMap<>(dto.getEnv() != null ? dto.getEnv() : Map.of());
        env.putIfAbsent("BICLOUD_MESH_BASE",
                "http://" + gatewayAlias + ":" + gatewayPort
                + "/_bicloud/mesh/" + dto.getProjectName());
        dto.setEnv(env);

        ContainerCreateResponse response = dockerContainerService.createAndStart(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{containerId}/stop")
    public ResponseEntity<Void> stopContainer(@PathVariable String containerId) {

        dockerContainerService.stopContainer(containerId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{containerId}/restart")
    public ResponseEntity<Void> restartContainer(@PathVariable String containerId) {

        dockerContainerService.restartContainer(containerId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{containerId}")
    public ResponseEntity<Void> removeContainer(@PathVariable String containerId) {

        try {
            dockerContainerService.stopContainer(containerId);
        } catch (Exception e) {
            log.warn("Stop failed before remove (container may already be stopped): {}",
                    e.getMessage());
        }

        dockerContainerService.removeContainer(containerId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{containerId}/logs")
    public ResponseEntity<Map<String, String>> getContainerLogs(
            @PathVariable String containerId,
            @RequestParam(defaultValue = "100") int tail) {

        String logs = dockerContainerService.getContainerLogs(containerId, tail);
        return ResponseEntity.ok(Map.of("containerId", containerId, "logs", logs));
    }
}
