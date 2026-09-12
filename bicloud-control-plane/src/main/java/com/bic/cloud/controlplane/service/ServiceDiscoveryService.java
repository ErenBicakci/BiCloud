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

    @Value("${bicloud.gateway.alias:bicloud-gateway}")
    private String gatewayAlias;

    @Value("${bicloud.gateway.port:9000}")
    private int gatewayPort;

    public String meshBaseUrl(String projectName) {
        return "http://" + gatewayAlias + ":" + gatewayPort + "/_bicloud/mesh/" + projectName;
    }
}
