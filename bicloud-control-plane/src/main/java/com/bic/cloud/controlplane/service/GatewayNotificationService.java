package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.model.ContainerInstance;
import com.bic.cloud.controlplane.model.WorkerState;
import com.bic.cloud.controlplane.repository.ContainerInstanceRepository;
import com.bic.cloud.controlplane.repository.WorkerStateRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sends container register/deregister and resync notifications to gateways.
 * Each machine runs its own gateway, so a container is registered with the
 * gateway of the worker it lives on (http://workerIp:port); if the worker IP
 * is unknown, the bicloud.gateway.url fallback is used.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GatewayNotificationService {

    private static final String API_KEY_HEADER = "X-Api-Key";

    // used when the worker IP is unknown (single machine / dev environment)
    @Value("${bicloud.gateway.url:http://localhost:9000}")
    private String fallbackGatewayUrl;

    // the same port is assumed on every machine
    @Value("${bicloud.gateway.port:9000}")
    private int gatewayPort;

    // deliberately no default, missing config should fail at startup
    @Value("${bicloud.gateway.api-key}")
    private String gatewayApiKey;

    private final RestClient restClient;
    private final ContainerInstanceRepository containerInstanceRepository;
    private final WorkerStateRepository workerStateRepository;


    @PostConstruct
    public void resyncOnStartup() {
        log.info("Gateway startup resync starting...");
        resyncAll();
    }

    /**
     * Registers a newly started container with the gateway of its worker.
     */
    public void register(ContainerInstance instance) {
        String containerIp = instance.getContainerIp();
        if (containerIp == null || containerIp.isBlank()) {
            log.warn("Gateway register skipped - no containerIp: instanceId={}", instance.getId());
            return;
        }

        String projectName   = instance.getProjectImage().getProject().getName();
        String serviceName   = instance.getProjectImage().getServiceName();
        int    containerPort = instance.getProjectImage().getContainerPort();
        String gwUrl         = resolveGatewayUrl(instance);

        try {
            restClient.post()
                    .uri(gwUrl + "/gateway/register")
                    .header(API_KEY_HEADER, gatewayApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "projectId",   instance.getProjectImage().getProject().getId(),
                            "projectName", projectName,
                            "serviceName", serviceName,
                            "instanceIp",  containerIp,
                            "port",        containerPort,
                            "instanceId",  instance.getId().toString()
                    ))
                    .retrieve()
                    .toBodilessEntity();

            log.info("Gateway register {}.{} -> {}:{} (gateway={})",
                    serviceName, projectName, containerIp, containerPort, gwUrl);

        } catch (Exception e) {
            log.warn("Gateway register failed (non-critical) {}.{} @ {}: {}",
                    serviceName, projectName, gwUrl, e.getMessage());
        }
    }

    /**
     * Removes a stopped/deleted container from the gateway of its worker.
     */
    public void deregister(ContainerInstance instance) {
        String containerIp = instance.getContainerIp();
        if (containerIp == null || containerIp.isBlank()) {
            log.debug("Gateway deregister skipped - no containerIp: instanceId={}", instance.getId());
            return;
        }

        String projectName   = instance.getProjectImage().getProject().getName();
        String serviceName   = instance.getProjectImage().getServiceName();
        int    containerPort = instance.getProjectImage().getContainerPort();
        String gwUrl         = resolveGatewayUrl(instance);

        try {
            restClient.method(HttpMethod.DELETE)
                    .uri(gwUrl + "/gateway/deregister")
                    .header(API_KEY_HEADER, gatewayApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "projectName", projectName,
                            "serviceName", serviceName,
                            "instanceIp",  containerIp,
                            "port",        containerPort
                    ))
                    .retrieve()
                    .toBodilessEntity();

            log.info("Gateway deregister {}.{} -> {}:{} (gateway={})",
                    serviceName, projectName, containerIp, containerPort, gwUrl);

        } catch (Exception e) {
            log.warn("Gateway deregister failed (non-critical) {}.{} @ {}: {}",
                    serviceName, projectName, gwUrl, e.getMessage());
        }
    }

    /**
     * Re-registers every RUNNING container in the DB with the gateways.
     * Used after a gateway restart (in-memory routes lost), at CP startup and
     * by the manual sync button in the admin panel.
     */
    public int resyncAll() {
        List<ContainerInstance> running = containerInstanceRepository
                .findAllByStatus(ContainerInstance.InstanceStatus.RUNNING);

        int registered = 0;
        for (ContainerInstance instance : running) {
            if (instance.getContainerIp() != null) {
                register(instance);
                registered++;
            }
        }

        log.info("Gateway resync finished: {}/{} instance(s) registered.",
                registered, running.size());
        return registered;
    }

    // worker IP comes from WorkerState, otherwise fallback URL
    private String resolveGatewayUrl(ContainerInstance instance) {
        try {
            UUID workerId = instance.getWorkerNode().getId();
            Optional<WorkerState> state = workerStateRepository.findById(workerId);
            if (state.isPresent() && state.get().getIpAddress() != null
                    && !state.get().getIpAddress().isBlank()) {
                return "http://" + state.get().getIpAddress() + ":" + gatewayPort;
            }
        } catch (Exception e) {
            log.debug("Could not resolve worker IP, using fallback: {}", e.getMessage());
        }

        log.debug("Worker IP unknown, using fallback gateway: {}", fallbackGatewayUrl);
        return fallbackGatewayUrl;
    }
}
