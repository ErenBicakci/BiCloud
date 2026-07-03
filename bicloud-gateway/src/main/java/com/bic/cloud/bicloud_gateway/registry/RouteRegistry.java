package com.bic.cloud.bicloud_gateway.registry;

import com.bic.cloud.bicloud_gateway.docker.GatewayNetworkManager;
import com.bic.cloud.bicloud_gateway.model.ServiceInstance;
import com.bic.cloud.bicloud_gateway.model.ServiceRoute;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


@Component
@RequiredArgsConstructor
@Slf4j
public class RouteRegistry {


    private final ConcurrentHashMap<String, ServiceRoute> routes = new ConcurrentHashMap<>();

    private final GatewayNetworkManager networkManager;


    public void register(String projectName, String serviceName,
                         String ip, int port, String instanceId, boolean exposeExternally) {

        String key = ServiceRoute.buildKey(projectName, serviceName);
        boolean isFirstInstanceForProject = !hasAnyInstanceForProject(projectName);

        ServiceRoute route = routes.computeIfAbsent(key, k -> {
            log.info("New route created: [{}]", k);
            return ServiceRoute.builder()
                    .routeKey(k)
                    .projectName(projectName.toLowerCase())
                    .serviceName(serviceName.toLowerCase())
                    .build();
        });

        synchronized (route) {
            route.setExposeExternally(exposeExternally);
            route.setUpdatedAt(java.time.Instant.now());

            boolean alreadyExists = route.getInstances().stream()
                    .anyMatch(i -> i.isSameEndpoint(ip, port));

            if (alreadyExists) {
                log.debug("[{}] Instance already registered, policy refreshed: {}:{} | external={}",
                        key, ip, port, exposeExternally);
                return;
            }

            route.getInstances().add(
                    ServiceInstance.builder()
                            .instanceId(instanceId)
                            .ip(ip)
                            .port(port)
                            .build()
            );

            log.info("[{}] Instance added -> {}:{} | Total: {} | external={}",
                    key, ip, port, route.getInstances().size(), exposeExternally);
        }

        // if this is the first instance -> connect the gateway to that project's network
        if (isFirstInstanceForProject) {
            log.info("[{}] first instance -> connecting to the Docker network for {}", key, projectName);
            networkManager.connectToNetwork(projectName);
        }
    }


    public boolean deregister(String projectName, String serviceName, String ip, int port) {
        String key = ServiceRoute.buildKey(projectName, serviceName);
        ServiceRoute route = routes.get(key);

        if (route == null) {
            log.warn("[{}] Deregister failed: route not found", key);
            return false;
        }

        boolean removed;
        synchronized (route) {
            removed = route.getInstances().removeIf(i -> i.isSameEndpoint(ip, port));

            if (removed) {
                log.info("[{}] Instance removed -> {}:{} | Remaining: {}",
                        key, ip, port, route.getInstances().size());

                if (route.getInstances().isEmpty()) {
                    routes.remove(key);
                    log.info("[{}] Route deleted (no instances left)", key);
                }
            } else {
                log.warn("[{}] Instance not found: {}:{}", key, ip, port);
            }
        }

        if (removed && !hasAnyInstanceForProject(projectName)) {
            log.info("All instances removed for project '{}' -> disconnecting from the Docker network", projectName);
            networkManager.disconnectFromNetwork(projectName);
        }

        return removed;
    }

    //
    public Optional<ServiceInstance> resolve(String projectName, String serviceName) {
        String key = ServiceRoute.buildKey(projectName, serviceName);
        ServiceRoute route = routes.get(key);
        if (route == null) return Optional.empty();
        return Optional.ofNullable(route.nextInstance());
    }

    public boolean isExternallyExposed(String projectName, String serviceName) {
        String key = ServiceRoute.buildKey(projectName, serviceName);
        ServiceRoute route = routes.get(key);
        return route != null && route.isExposeExternally();
    }

    /**
     * Reconciles the registry with local Docker reality: removes instance records
     * for IPs NOT currently present in each project's network. Even if a push is
     * missed, stale entries get cleaned up by the next scan - no CP restart needed.
     *
     * @param liveIpsByProject project name -> live container IPs on that project's network.
     *                         A project NOT in the map (network deleted) is cleared entirely.
     * @return number of removed instances
     */
    public int pruneStale(Map<String, java.util.Set<String>> liveIpsByProject) {
        int removed = 0;
        for (ServiceRoute route : routes.values()) {
            java.util.Set<String> live = liveIpsByProject
                    .getOrDefault(route.getProjectName(), java.util.Set.of());
            synchronized (route) {
                List<ServiceInstance> stale = route.getInstances().stream()
                        .filter(i -> !live.contains(i.getIp()))
                        .toList();
                for (ServiceInstance s : stale) {
                    route.getInstances().remove(s);
                    removed++;
                    log.info("[{}] Pruned stale instance -> {}:{} (absent from Docker network)",
                            route.getRouteKey(), s.getIp(), s.getPort());
                }
                if (route.getInstances().isEmpty()) {
                    routes.remove(route.getRouteKey());
                    log.info("[{}] Route deleted (no instances left after pruning)", route.getRouteKey());
                }
            }
        }
        return removed;
    }

    public Map<String, List<ServiceInstance>> snapshot() {
        return routes.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> Collections.unmodifiableList(e.getValue().getInstances())
                ));
    }

    public int routeCount() {
        return routes.size();
    }

    public long instanceCount() {
        return routes.values().stream()
                .mapToLong(r -> r.getInstances().size())
                .sum();
    }


    private boolean hasAnyInstanceForProject(String projectName) {
        String lowerProject = projectName.toLowerCase();
        return routes.values().stream()
                .anyMatch(r -> lowerProject.equals(r.getProjectName())
                        && !r.getInstances().isEmpty());
    }
}
