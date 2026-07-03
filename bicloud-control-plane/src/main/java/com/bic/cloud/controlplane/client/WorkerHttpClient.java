package com.bic.cloud.controlplane.client;

import com.bic.cloud.controlplane.dto.WorkerContainerCreateRequest;
import com.bic.cloud.controlplane.dto.WorkerContainerCreateResponse;
import com.bic.cloud.controlplane.exception.WorkerCommunicationException;
import com.bic.cloud.controlplane.exception.WorkerNotFoundException;
import com.bic.cloud.controlplane.model.WorkerNode;
import com.bic.cloud.controlplane.model.WorkerState;
import com.bic.cloud.controlplane.repository.WorkerStateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@Component
public class WorkerHttpClient {

    /** Short read timeout - stop/remove/logs must fail fast. */
    private final RestClient restClient;

    /** Long read timeout - create may wait behind a cold image pull. */
    private final RestClient deployRestClient;

    private final WorkerStateRepository stateRepository;

    public WorkerHttpClient(RestClient restClient,
                            @Qualifier("deployRestClient") RestClient deployRestClient,
                            WorkerStateRepository stateRepository) {
        this.restClient = restClient;
        this.deployRestClient = deployRestClient;
        this.stateRepository = stateRepository;
    }

    @Value("${bicloud.api-key}")
    private String apiKey;

    private static final String API_KEY_HEADER = "X-Api-Key";

    public WorkerContainerCreateResponse createContainer(WorkerNode targetWorker,
                                                          WorkerContainerCreateRequest dto) {

        String workerUrl = resolveWorkerUrl(targetWorker);

        try {
            WorkerContainerCreateResponse response = deployRestClient.post()
                    .uri(workerUrl + "/api/containers/create")
                    .header(API_KEY_HEADER, apiKey)
                    .body(dto)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, resp) -> {
                        throw new WorkerCommunicationException(
                                targetWorker.getWorkerName(),
                                "HTTP " + resp.getStatusCode());
                    })
                    .body(WorkerContainerCreateResponse.class);

            log.info("Container created on worker {} for service={}: containerId={}",
                    targetWorker.getWorkerName(),
                    dto.getServiceName(),
                    response != null ? response.getContainerId() : "null");

            return response;

        } catch (WorkerCommunicationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to create container on worker {}. service={}",
                    targetWorker.getWorkerName(), dto.getServiceName(), e);
            throw new WorkerCommunicationException(
                    targetWorker.getWorkerName(),
                    "Container creation failed for service " + dto.getServiceName(), e);
        }
    }

    public void stopContainer(WorkerNode targetWorker, String dockerContainerId) {

        String workerUrl = resolveWorkerUrl(targetWorker);

        try {
            restClient.post()
                    .uri(workerUrl + "/api/containers/" + dockerContainerId + "/stop")
                    .header(API_KEY_HEADER, apiKey)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Container {} stopped on worker {}", dockerContainerId, targetWorker.getWorkerName());

        } catch (Exception e) {
            log.error("Failed to stop container {} on worker {}",
                    dockerContainerId, targetWorker.getWorkerName(), e);
            throw new WorkerCommunicationException(
                    targetWorker.getWorkerName(),
                    "Stop container failed: " + dockerContainerId, e);
        }
    }

    public void stopAndRemoveContainer(WorkerNode targetWorker, String dockerContainerId) {

        String workerUrl = resolveWorkerUrl(targetWorker);

        try {
            restClient.delete()
                    .uri(workerUrl + "/api/containers/" + dockerContainerId)
                    .header(API_KEY_HEADER, apiKey)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Container {} stopped & removed on worker {}",
                    dockerContainerId, targetWorker.getWorkerName());

        } catch (Exception e) {
            log.error("Failed to remove container {} on worker {}",
                    dockerContainerId, targetWorker.getWorkerName(), e);
            throw new WorkerCommunicationException(
                    targetWorker.getWorkerName(),
                    "Remove container failed: " + dockerContainerId, e);
        }
    }

    public String getContainerLogs(WorkerNode targetWorker, String dockerContainerId, int tailLines) {

        String workerUrl = resolveWorkerUrl(targetWorker);

        try {
            Map<String, String> response = restClient.get()
                    .uri(workerUrl + "/api/containers/" + dockerContainerId + "/logs?tail=" + tailLines)
                    .header(API_KEY_HEADER, apiKey)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, String>>() {});

            return response != null ? response.getOrDefault("logs", "") : "";

        } catch (Exception e) {
            log.error("Failed to get logs for container {} on worker {}",
                    dockerContainerId, targetWorker.getWorkerName(), e);
            throw new WorkerCommunicationException(
                    targetWorker.getWorkerName(),
                    "Get logs failed for container: " + dockerContainerId, e);
        }
    }

    private String resolveWorkerUrl(WorkerNode worker) {

        WorkerState state = stateRepository.findById(worker.getId())
                .orElseThrow(() -> new WorkerNotFoundException(worker.getId()));

        String ip = state.getIpAddress();
        int port = worker.getServerPort();

        if (ip == null || ip.isBlank()) {
            throw new WorkerCommunicationException(
                    worker.getWorkerName(), "IP address is not available");
        }

        return "http://" + ip + ":" + port;
    }
}
