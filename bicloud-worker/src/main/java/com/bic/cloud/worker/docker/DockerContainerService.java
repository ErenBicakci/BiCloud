package com.bic.cloud.worker.docker;

import com.bic.cloud.worker.dto.ContainerCreateRequest;
import com.bic.cloud.worker.dto.ContainerCreateResponse;
import com.bic.cloud.worker.exception.DockerOperationException;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerContainerService {

    static final String LABEL_PROJECT = "bicloud.project";
    static final String LABEL_SERVICE = "bicloud.service";
    static final String LABEL_PORT    = "bicloud.port";

    private final DockerClient         dockerClient;
    private final DockerNetworkService dockerNetworkService;

    public ContainerCreateResponse createAndStart(ContainerCreateRequest req) throws InterruptedException {

        String networkName = DockerNetworkService.networkName(req.getProjectName());
        String networkId   = dockerNetworkService.ensureNetworkExists(req.getProjectName());

        pullImageIfMissing(req.getImageName());

        ExposedPort exposedPort = ExposedPort.tcp(req.getContainerPort());

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withMemory(req.getMemoryLimitMb().longValue() * 1024 * 1024)
                .withNanoCPUs(req.getCpuLimitMillicores().longValue() * 1_000_000L)
                .withCapDrop(Capability.ALL)
                .withCapAdd(
                    Capability.CHOWN,
                    Capability.SETUID,
                    Capability.SETGID,
                    Capability.NET_BIND_SERVICE
                )
                .withPidsLimit(100L);

        List<String> envList = req.getEnv() == null
                ? List.of()
                : req.getEnv().entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.toList());

        String safeProject  = sanitizeForDockerName(req.getProjectName(), "projectName");
        String safeService  = sanitizeForDockerName(req.getServiceName(), "serviceName");
        String containerName = safeProject + "-" + safeService + "-" + UUID.randomUUID();

        CreateContainerResponse created = dockerClient.createContainerCmd(req.getImageName())
                .withName(containerName)
                .withHostConfig(hostConfig)
                .withExposedPorts(exposedPort)
                .withEnv(envList)
                .withLabels(Map.of(
                        "bicloud.managed", "true",
                        LABEL_PROJECT,     req.getProjectName(),
                        LABEL_SERVICE,     req.getServiceName(),
                        LABEL_PORT,        String.valueOf(req.getContainerPort())
                ))
                .exec();

        String containerId = created.getId();

        // If any step after container creation blows up, the created but
        // untracked container lingers in Docker (no response reaches the CP,
        // so its record has no dockerContainerId -> reconciliation can't clean it).
        // Hence on failure we force-remove the half-created container.
        try {
            dockerClient.connectToNetworkCmd()
                    .withContainerId(containerId)
                    .withNetworkId(networkId)
                    .withContainerNetwork(new ContainerNetwork()
                            .withAliases(List.of(req.getServiceName())))
                    .exec();

            // admin-granted egress: also attach to this project's egress bridge
            // (the internal project network has no outbound route). Per-project
            // so egress-enabled tenants never share an L2 segment.
            if (req.isAllowInternet()) {
                String egressNetworkId = dockerNetworkService.ensureEgressNetworkExists(req.getProjectName());
                dockerClient.connectToNetworkCmd()
                        .withContainerId(containerId)
                        .withNetworkId(egressNetworkId)
                        .exec();
                log.info("Container {} attached to egress network (allowInternet=true)", containerName);
            }

            // Fail-CLOSED: the container is born on Docker's default bridge (which
            // reaches the internet) and must be detached before it starts. If the
            // disconnect fails we must NOT start it - an isolated (allowInternet=false)
            // service would otherwise leak onto the internet. Verify with inspect and
            // let the outer catch clean up the half-created container.
            try {
                dockerClient.disconnectFromNetworkCmd()
                        .withNetworkId("bridge")
                        .withContainerId(containerId)
                        .withForce(false)
                        .exec();
            } catch (Exception e) {
                log.warn("Default bridge disconnect call failed for {}: {}. Verifying isolation.",
                        containerId, e.getMessage());
            }
            if (isConnectedToBridge(containerId)) {
                throw new IllegalStateException(
                        "Container still attached to the default bridge; refusing to start (would break network isolation)");
            }

            dockerClient.startContainerCmd(containerId).exec();

            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            String containerIp = extractContainerIp(inspect, networkName);

            log.info("Container started: name={} network={} internalIp={}",
                    containerName, networkName, containerIp);

            return new ContainerCreateResponse(containerId, containerIp,
                    "Container created & started successfully");

        } catch (RuntimeException e) {
            log.error("Container {} setup failed ({}). Cleaning up the half-created container.",
                    containerId, e.getMessage());
            try {
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                log.info("Half-created container cleaned up: {}", containerId);
            } catch (Exception cleanupEx) {
                log.error("Could not clean up half-created container {}: {}", containerId, cleanupEx.getMessage());
            }
            throw new DockerOperationException(containerId, "create", e);
        }
    }

    /**
     * "IfNotPresent" pull policy: an unconditional pull contacts the registry
     * on EVERY deploy even when the image is already local - needless latency
     * and it eats into Docker Hub rate limits. Tags are treated as immutable
     * here; a user who republishes the same tag redeploys with a version bump
     * (or the image can be removed manually on the worker).
     */
    private void pullImageIfMissing(String imageName) throws InterruptedException {
        try {
            dockerClient.inspectImageCmd(imageName).exec();
            log.debug("Image already present locally, skipping pull: {}", imageName);
            return;
        } catch (NotFoundException e) {
            // not local - fall through to pull
        }

        log.info("Pulling image: {}", imageName);
        boolean pulled = dockerClient.pullImageCmd(imageName)
                .start()
                .awaitCompletion(10, TimeUnit.MINUTES);
        if (!pulled) {
            throw new DockerOperationException(imageName, "pull",
                    new RuntimeException("Image pull timed out: " + imageName));
        }
    }

    public void stopContainer(String containerId) {
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(10).exec();
            log.info("Container stopped: {}", containerId);
        } catch (Exception e) {
            throw new DockerOperationException(containerId, "stop", e);
        }
    }

    public void restartContainer(String containerId) {
        try {
            dockerClient.restartContainerCmd(containerId).withTimeout(10).exec();
            log.info("Container restarted: {}", containerId);
        } catch (Exception e) {
            throw new DockerOperationException(containerId, "restart", e);
        }
    }

    public void removeContainer(String containerId) {
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            log.info("Container removed: {}", containerId);
        } catch (Exception e) {
            throw new DockerOperationException(containerId, "remove", e);
        }
    }

    public String getContainerLogs(String containerId, int tailLines) {
        try {
            StringBuilder logs = new StringBuilder();
            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true).withStdErr(true)
                    .withTail(tailLines).withTimestamps(true)
                    .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<Frame>() {
                        @Override public void onNext(Frame f) { logs.append(new String(f.getPayload())); }
                    })
                    .awaitCompletion(10, TimeUnit.SECONDS);
            return logs.toString();
        } catch (Exception e) {
            throw new DockerOperationException(containerId, "logs", e);
        }
    }

    public List<Container> listAllContainers() {
        return dockerClient.listContainersCmd().withShowAll(true).exec();
    }


    private String extractContainerIp(InspectContainerResponse inspect, String networkName) {
        var networks = inspect.getNetworkSettings().getNetworks();
        if (networks == null) return null;
        var net = networks.get(networkName);
        if (net == null) return null;
        String ip = net.getIpAddress();
        return (ip != null && !ip.isBlank()) ? ip : null;
    }

    /**
     * True if the container is still attached to Docker's default "bridge"
     * network. Used to enforce fail-closed isolation: a container that could not
     * be detached from the internet-facing bridge must not be started.
     */
    private boolean isConnectedToBridge(String containerId) {
        try {
            var networks = dockerClient.inspectContainerCmd(containerId)
                    .exec().getNetworkSettings().getNetworks();
            return networks != null && networks.containsKey("bridge");
        } catch (Exception e) {
            // cannot verify isolation -> treat as unsafe (fail-closed)
            log.warn("Could not inspect networks for {} ({}); assuming still bridged.",
                    containerId, e.getMessage());
            return true;
        }
    }

    private static final Pattern DOCKER_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.\\-]*$");

    private String sanitizeForDockerName(String value, String fieldName) {
        if (value == null || value.isBlank())
            throw new DockerOperationException(fieldName, "validate",
                    new IllegalArgumentException(fieldName + " must not be blank"));
        if (!DOCKER_NAME_PATTERN.matcher(value).matches())
            throw new DockerOperationException(value, "validate",
                    new IllegalArgumentException("Invalid value for '" + fieldName + "': '" + value + "'"));
        return value;
    }
}
