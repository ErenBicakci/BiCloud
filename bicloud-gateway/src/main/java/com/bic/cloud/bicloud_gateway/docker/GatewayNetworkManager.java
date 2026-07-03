package com.bic.cloud.bicloud_gateway.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


@Component
@Slf4j
public class GatewayNetworkManager {

    /// Docker network naming convention: "bicloud-{projectName}"
    private static final String NETWORK_PREFIX = "bicloud-";

    /**
     * The gateway's DNS alias on every tenant network. Containers reach the mesh
     * proxy at the same address on every machine: http://bicloud-gateway:9000/_bicloud/mesh/...
     */
    private static final String GATEWAY_NETWORK_ALIAS = "bicloud-gateway";

    /**
     * Infrastructure networks excluded from the scan.
     * These are not tenant isolation networks but inter-service networks.
     */
    private static final Set<String> EXCLUDED_NETWORKS = Set.of(
            "bicloud-infra"
    );

    /**
     * Per-project egress bridges (bicloud-egress-*) are internet-uplink networks,
     * not tenant service networks. The gateway must not join them or fold their
     * subnets into the mesh source-IP map - doing so would corrupt tenant
     * isolation. They are excluded from the scan by this prefix.
     */
    private static final String EGRESS_PREFIX = "bicloud-egress-";

    @Value("${bicloud.gateway.container-name:}")
    private String configuredContainerName;

    @Value("${bicloud.gateway.docker-host:unix:///var/run/docker.sock}")
    private String dockerHost;

    /** Names of currently connected networks. Prevents reconnecting, aids monitoring. */
    private final Set<String> connectedNetworks = ConcurrentHashMap.newKeySet();

    /**
     * Subnet -> project map for tenant isolation (CIDR string -> project name).
     * Whichever subnet the source IP of a mesh request falls into determines its
     * project; MeshRoutingFilter compares that against the project in the path.
     */
    private volatile Map<String, String> subnetToProject = Map.of();

    private DockerClient dockerClient;
    private String gatewayContainerId;



    @PostConstruct
    public void init() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost)
                .build();

        var httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(URI.create(dockerHost))
                .maxConnections(20)
                .connectionTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(10))
                .build();

        this.dockerClient   = DockerClientImpl.getInstance(config, httpClient);
        this.gatewayContainerId = resolveGatewayContainerId();

        log.info("GatewayNetworkManager started | container={} | dockerHost={}",
                gatewayContainerId, dockerHost);

        // scan and connect to all existing bicloud-* networks at startup
        scanAndConnectAll();
    }


    @Scheduled(fixedDelayString = "${bicloud.gateway.network-scan-interval-ms:60000}")
    public void scanAndConnectAll() {
        log.debug("Docker network scan starting...");

        try {
            List<Network> bicloudNetworks = dockerClient.listNetworksCmd()
                    .exec()
                    .stream()
                    .filter(n -> n.getName().startsWith(NETWORK_PREFIX))
                    .filter(n -> !EXCLUDED_NETWORKS.contains(n.getName()))
                    .filter(n -> !n.getName().startsWith(EGRESS_PREFIX))
                    .collect(Collectors.toList());

            if (bicloudNetworks.isEmpty()) {
                log.debug("No bicloud-* networks found.");
                return;
            }

            log.debug("Detected {} bicloud-* network(s).", bicloudNetworks.size());

            // connect to every network found
            for (Network network : bicloudNetworks) {
                connectToNetworkById(network.getName(), network.getId());
            }

            // drop networks that no longer exist in Docker
            Set<String> existingNames = bicloudNetworks.stream()
                    .map(Network::getName)
                    .collect(Collectors.toSet());

            connectedNetworks.removeIf(name -> !existingNames.contains(name));

            rebuildSubnetMap(bicloudNetworks);

        } catch (Exception e) {
            log.error("Error during network scan: {}", e.getMessage(), e);
        }
    }

    public void connectToNetwork(String projectName) {
        String networkName = NETWORK_PREFIX + projectName.toLowerCase();
        String networkId   = findNetworkId(networkName);

        if (networkId == null) {
            log.warn("Network not found: {} - the worker may not have created it yet, " +
                     "will retry on the next scan.", networkName);
            return;
        }

        connectToNetworkById(networkName, networkId);
    }


    public void disconnectFromNetwork(String projectName) {
        String networkName = NETWORK_PREFIX + projectName.toLowerCase();

        if (!connectedNetworks.contains(networkName)) {
            log.debug("Not connected anyway: {}", networkName);
            return;
        }

        try {
            String networkId = findNetworkId(networkName);
            if (networkId == null) {
                connectedNetworks.remove(networkName);
                return;
            }

            dockerClient.disconnectFromNetworkCmd()
                    .withContainerId(gatewayContainerId)
                    .withNetworkId(networkId)
                    .exec();

            connectedNetworks.remove(networkName);
            log.info("Gateway disconnected from network {}", networkName);

        } catch (NotFoundException e) {
            connectedNetworks.remove(networkName);
            log.debug("Disconnect: network already deleted: {}", networkName);
        } catch (Exception e) {
            log.error("Failed to disconnect from network: {} - {}", networkName, e.getMessage(), e);
        }
    }

    private void connectToNetworkById(String networkName, String networkId) {
        if (connectedNetworks.contains(networkName)) {
            return; // already connected
        }

        try {
            dockerClient.connectToNetworkCmd()
                    .withContainerId(gatewayContainerId)
                    .withNetworkId(networkId)
                    .withContainerNetwork(new com.github.dockerjava.api.model.ContainerNetwork()
                            .withAliases(List.of(GATEWAY_NETWORK_ALIAS)))
                    .exec();

            connectedNetworks.add(networkName);
            log.info("Gateway connected to network {} (alias={})", networkName, GATEWAY_NETWORK_ALIAS);

        } catch (ConflictException e) {
            connectedNetworks.add(networkName);
            log.debug("Gateway was already connected to network {} (409 conflict)", networkName);
        } catch (NotFoundException e) {
            // the gateway is not running as a Docker container (dev environment).
            // docker network connect can't work - expected, pass silently.
            log.warn("Gateway container not found ('{}') - it may be running as a plain Java " +
                     "process in dev mode. Run the gateway as a Docker container for full routing.",
                     gatewayContainerId);
        } catch (com.github.dockerjava.api.exception.DockerException e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                connectedNetworks.add(networkName);
                log.debug("Gateway was already connected to network {} (already exists)", networkName);
            } else {
                log.error("Failed to connect to network: {} - {}", networkName, e.getMessage(), e);
            }
        } catch (Exception e) {
            log.error("Failed to connect to network: {} - {}", networkName, e.getMessage(), e);
        }
    }

    //determines the gateway's own container name/ID.
    private String resolveGatewayContainerId() {
        if (configuredContainerName != null && !configuredContainerName.isBlank()) {
            log.info("Gateway container name (config): {}", configuredContainerName);
            return configuredContainerName;
        }

        String hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) {
            log.info("Gateway container name (HOSTNAME): {}", hostname);
            return hostname;
        }

        log.warn("Could not determine the gateway container name - falling back to 'bicloud-gateway'. " +
                 "Define 'container_name: bicloud-gateway' in docker-compose.");
        return "bicloud-gateway";
    }

    /**
     * Returns which tenant project the given IPv4 address belongs to.
     * Empty if the IP falls into no bicloud-* subnet (not a container -> no tenant).
     */
    public java.util.Optional<String> projectForIp(String ip) {
        long addr = ipv4ToLong(ip);
        if (addr < 0) return java.util.Optional.empty();

        for (Map.Entry<String, String> e : subnetToProject.entrySet()) {
            if (cidrContains(e.getKey(), addr)) {
                return java.util.Optional.of(e.getValue());
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * For each tenant project, returns the container IPs that currently really
     * exist on that project's Docker network (excluding the gateway itself).
     * RouteRegistry is compared against this "local truth" to purge stale entries.
     */
    public Map<String, Set<String>> liveIpsByProject() {
        Map<String, Set<String>> result = new java.util.HashMap<>();
        try {
            List<Network> networks = dockerClient.listNetworksCmd().exec().stream()
                    .filter(n -> n.getName().startsWith(NETWORK_PREFIX))
                    .filter(n -> !EXCLUDED_NETWORKS.contains(n.getName()))
                    .toList();

            for (Network n : networks) {
                String project = n.getName().substring(NETWORK_PREFIX.length());
                Set<String> ips = ConcurrentHashMap.newKeySet();
                // listNetworks doesn't return container details - each needs an inspect
                Network detail = dockerClient.inspectNetworkCmd().withNetworkId(n.getId()).exec();
                if (detail.getContainers() != null) {
                    detail.getContainers().forEach((id, container) -> {
                        String addr = container.getIpv4Address(); // "172.23.0.6/16"
                        if (addr != null && !addr.isBlank()) {
                            int slash = addr.indexOf('/');
                            ips.add(slash > 0 ? addr.substring(0, slash) : addr);
                        }
                    });
                }
                result.put(project, ips);
            }
        } catch (Exception e) {
            log.error("liveIpsByProject failed: {}", e.getMessage());
            return Map.of(); // return empty - the caller does NOT prune (avoid accidental deletion)
        }
        return result;
    }

    /** Rebuilds the subnet(CIDR) -> project name map from the network list. */
    private void rebuildSubnetMap(List<Network> bicloudNetworks) {
        Map<String, String> fresh = new java.util.HashMap<>();
        for (Network n : bicloudNetworks) {
            String project = n.getName().substring(NETWORK_PREFIX.length());
            if (n.getIpam() == null || n.getIpam().getConfig() == null) continue;
            for (Network.Ipam.Config cfg : n.getIpam().getConfig()) {
                if (cfg.getSubnet() != null && cfg.getSubnet().contains(".")) { // IPv4 only
                    fresh.put(cfg.getSubnet(), project);
                }
            }
        }
        this.subnetToProject = Map.copyOf(fresh);
        log.debug("Tenant subnet map updated: {}", subnetToProject);
    }

    private static boolean cidrContains(String cidr, long addr) {
        int slash = cidr.indexOf('/');
        if (slash < 0) return false;
        long base = ipv4ToLong(cidr.substring(0, slash));
        if (base < 0) return false;
        int prefix = Integer.parseInt(cidr.substring(slash + 1));
        long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return (addr & mask) == (base & mask);
    }

    private static long ipv4ToLong(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return -1;
        try {
            long value = 0;
            for (String p : parts) {
                int octet = Integer.parseInt(p);
                if (octet < 0 || octet > 255) return -1;
                value = (value << 8) | octet;
            }
            return value;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Finds the ID of the Docker network that exactly matches the given name.
     */
    private String findNetworkId(String networkName) {
        return dockerClient.listNetworksCmd()
                .withNameFilter(networkName)
                .exec()
                .stream()
                .filter(n -> networkName.equals(n.getName()))
                .map(Network::getId)
                .findFirst()
                .orElse(null);
    }
}
