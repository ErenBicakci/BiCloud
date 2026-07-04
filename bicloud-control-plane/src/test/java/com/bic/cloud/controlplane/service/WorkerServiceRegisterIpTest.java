package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.dto.WorkerRegisterRequest;
import com.bic.cloud.controlplane.exception.InvalidWorkerIpException;
import com.bic.cloud.controlplane.model.WorkerNode;
import com.bic.cloud.controlplane.model.WorkerState;
import com.bic.cloud.controlplane.repository.ContainerInstanceRepository;
import com.bic.cloud.controlplane.repository.WorkerNodeRepository;
import com.bic.cloud.controlplane.repository.WorkerStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerServiceRegisterIpTest {

    @Mock
    private WorkerNodeRepository nodeRepository;

    @Mock
    private WorkerStateRepository stateRepository;

    @Mock
    private ContainerInstanceRepository containerInstanceRepository;

    @Mock
    private WorkerScoringService scoringService;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private WorkerService workerService;

    private WorkerNode node;
    private WorkerState state;

    @BeforeEach
    void setUp() {
        node = WorkerNode.builder()
                .id(UUID.randomUUID())
                .workerName("worker-a")
                .workerVersion("1.0.0")
                .totalCpuCores(8)
                .totalMemoryMb(8192)
                .serverPort(8081)
                .build();

        state = WorkerState.builder()
                .worker(node)
                .status(WorkerState.NodeStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("register -> stores the worker-advertised IP, not the socket IP")
    void registerStoresAdvertisedIp() {
        stubExistingWorker();

        workerService.register(registerRequest("10.10.0.12"), "172.18.0.4");

        assertThat(state.getIpAddress()).isEqualTo("10.10.0.12");
    }

    @Test
    @DisplayName("register -> rejects blank, loopback and malformed advertised IPs")
    void registerRejectsInvalidAdvertisedIp() {
        String[] invalidIps = {
                null,
                "",
                " ",
                "localhost",
                "127.0.0.1",
                "127.20.30.40",
                "0.0.0.0",
                "10.0.0",
                "10.0.0.300",
                "10.0.x.1"
        };

        for (String ip : invalidIps) {
            assertThatThrownBy(() -> workerService.register(registerRequest(ip), "10.10.0.99"))
                    .isInstanceOf(InvalidWorkerIpException.class);
        }

        verifyNoInteractions(nodeRepository, stateRepository);
    }

    private void stubExistingWorker() {
        when(nodeRepository.findByWorkerNameForUpdate("worker-a")).thenReturn(Optional.of(node));
        when(stateRepository.findById(node.getId())).thenReturn(Optional.of(state));
    }

    private WorkerRegisterRequest registerRequest(String ipAddress) {
        return WorkerRegisterRequest.builder()
                .workerName("worker-a")
                .ipAddress(ipAddress)
                .workerVersion("1.0.0")
                .totalCpuCores(8)
                .totalMemoryMb(8192)
                .serverPort(8081)
                .build();
    }
}
