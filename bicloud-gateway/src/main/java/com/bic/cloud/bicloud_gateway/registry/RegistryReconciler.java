package com.bic.cloud.bicloud_gateway.registry;

import com.bic.cloud.bicloud_gateway.client.ControlPlaneDiscoveryClient;
import com.bic.cloud.bicloud_gateway.docker.GatewayNetworkManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class RegistryReconciler {

    private final RouteRegistry registry;
    private final GatewayNetworkManager networkManager;
    private final ControlPlaneDiscoveryClient controlPlaneClient;

    @Scheduled(initialDelayString = "${bicloud.gateway.reconcile-initial-delay-ms:10000}",
               fixedDelayString   = "${bicloud.gateway.reconcile-interval-ms:30000}")
    public void reconcile() {
        Map<String, Set<String>> live = networkManager.liveIpsByProject();
        if (!live.isEmpty()) {
            int pruned = registry.pruneStale(live);
            if (pruned > 0) {
                log.info("Reconcile: pruned {} stale instance(s). routes={}, instances={}",
                        pruned, registry.routeCount(), registry.instanceCount());
            }
        }

        controlPlaneClient.requestResync().subscribe();
    }
}
