package com.bic.cloud.worker.client;

import com.bic.cloud.worker.dto.ContainerSnapshotRequest;
import com.bic.cloud.worker.dto.ContainerStatusUpdateRequest;
import com.bic.cloud.worker.dto.WorkerHeartbeatRequest;
import com.bic.cloud.worker.dto.WorkerRegisterRequest;
import com.bic.cloud.worker.dto.WorkerRegisterResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
@Slf4j
public class ControlPlaneHttpClient {

    private final RestClient restClient;

    @Value("${worker.control-plane-url}")
    private String controlPlaneUrl;

    @Value("${bicloud.api-key}")
    private String apiKey;

    private static final String API_KEY_HEADER = "X-Api-Key";

    public WorkerRegisterResponse sendRegister(WorkerRegisterRequest request) {

        WorkerRegisterResponse response = restClient.post()
                .uri(controlPlaneUrl + "/api/workers/register")
                .header(API_KEY_HEADER, apiKey)
                .body(request)
                .retrieve()
                .body(WorkerRegisterResponse.class);

        log.info("Register request sent. Received workerId: {}",
                response != null ? response.getWorkerId() : "null");

        return response;
    }

    public void sendHeartbeat(WorkerHeartbeatRequest request) {

        restClient.post()
                .uri(controlPlaneUrl + "/api/workers/heartbeat")
                .header(API_KEY_HEADER, apiKey)
                .body(request)
                .retrieve()
                .toBodilessEntity();

        log.debug("Heartbeat request sent for workerId: {}", request.getWorkerId());
    }

    public void sendDeregister(WorkerHeartbeatRequest request) {

        try {
            restClient.post()
                    .uri(controlPlaneUrl + "/api/workers/deregister")
                    .header(API_KEY_HEADER, apiKey)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Deregister request sent for workerId: {}", request.getWorkerId());
        } catch (Exception e) {
            log.warn("Deregister request failed (worker shutting down): {}", e.getMessage());
        }
    }

    public void sendContainerStatusUpdate(ContainerStatusUpdateRequest request) {

        try {
            restClient.post()
                    .uri(controlPlaneUrl + "/api/workers/container-status")
                    .header(API_KEY_HEADER, apiKey)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();

            log.debug("Container status update sent: containerId={}, status={}",
                    request.getDockerContainerId(), request.getStatus());
        } catch (Exception e) {
            log.warn("Container status update failed: {}", e.getMessage());
        }
    }

    public void sendContainerSnapshot(ContainerSnapshotRequest request) {

        try {
            restClient.post()
                    .uri(controlPlaneUrl + "/api/workers/container-snapshot")
                    .header(API_KEY_HEADER, apiKey)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();

            log.debug("Container snapshot sent: workerId={}, runningCount={}",
                    request.getWorkerId(),
                    request.getRunningContainerIds() == null ? 0 : request.getRunningContainerIds().size());
        } catch (Exception e) {
            log.warn("Container snapshot send failed: {}", e.getMessage());
        }
    }
}