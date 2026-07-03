package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.dto.ServiceEndpointDto;
import com.bic.cloud.controlplane.model.ContainerInstance;
import com.bic.cloud.controlplane.model.WorkerState;
import com.bic.cloud.controlplane.repository.ContainerInstanceRepository;
import com.bic.cloud.controlplane.repository.WorkerStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceDiscoveryService {

    private final ContainerInstanceRepository containerInstanceRepository;
    private final WorkerStateRepository workerStateRepository;

    /**
     * Endpoint used by the mesh proxy -
     * returns endpoint info for all RUNNING instances of the given project/service.
     */
    public List<ServiceEndpointDto> getEndpoints(String projectName, String serviceName) {
        List<ContainerInstance> instances =
                containerInstanceRepository.findRunningByProjectNameAndServiceName(projectName, serviceName);
        Map<UUID, WorkerState> stateMap = buildStateMap(instances);

        return instances.stream()
                .map(ci -> toEndpointDto(ci, stateMap.get(ci.getWorkerNode().getId())))
                .filter(Objects::nonNull)
                .toList();
    }

    private Map<UUID, WorkerState> buildStateMap(List<ContainerInstance> instances) {
        Set<UUID> workerIds = instances.stream()
                .map(ci -> ci.getWorkerNode().getId())
                .collect(Collectors.toSet());
        return workerStateRepository.findAllById(workerIds).stream()
                .collect(Collectors.toMap(WorkerState::getWorkerId, s -> s));
    }

    private ServiceEndpointDto toEndpointDto(ContainerInstance ci, WorkerState state) {
        String containerIp = ci.getContainerIp();
        if (containerIp == null || containerIp.isBlank()) return null;
        if (state == null) return null;

        return ServiceEndpointDto.builder()
                .serviceName(ci.getProjectImage().getServiceName())
                .containerIp(containerIp)
                .containerPort(ci.getProjectImage().getContainerPort())
                .workerIp(state.getIpAddress())
                .workerPort(ci.getWorkerNode().getServerPort())
                .workerId(ci.getWorkerNode().getId())
                .exposeExternally(ci.getProjectImage().isExposeExternally())
                .build();
    }

    /** The gateway's DNS alias on every tenant network (must match GatewayNetworkManager). */
    @Value("${bicloud.gateway.alias:bicloud-gateway}")
    private String gatewayAlias;

    /** Port the gateway container listens on. */
    @Value("${bicloud.gateway.port:9000}")
    private int gatewayPort;

    /**
     * The ONE address contract the platform hands to containers: the mesh base.
     *
     * The gateway joins every tenant network under the same alias, so this
     * address is identical on every machine; the app builds the target address
     * itself as {@code MESH_BASE + "/" + serviceName + path}.
     * Per-service envs (_URL/_HOST/_PORT) are deliberately not provided - the
     * _HOST alias only resolved on the same machine, which was misleading;
     * the platform offers no real DNS resolution.
     */
    public String meshBaseUrl(String projectName) {
        return "http://" + gatewayAlias + ":" + gatewayPort + "/_bicloud/mesh/" + projectName;
    }
}
