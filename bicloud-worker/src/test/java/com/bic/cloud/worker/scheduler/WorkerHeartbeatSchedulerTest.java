package com.bic.cloud.worker.scheduler;

import com.bic.cloud.worker.client.ControlPlaneHttpClient;
import com.bic.cloud.worker.config.WorkerStartup;
import com.bic.cloud.worker.metrics.WorkerMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerHeartbeatSchedulerTest {

    @Mock
    private ControlPlaneHttpClient client;

    @Mock
    private WorkerMetricsService metricsService;

    @Mock
    private WorkerStartup workerStartup;

    @InjectMocks
    private WorkerHeartbeatScheduler scheduler;

    @BeforeEach
    void setUp() {
        when(workerStartup.isRegistered()).thenReturn(true);
        when(workerStartup.getWorkerId()).thenReturn(UUID.randomUUID());
        when(metricsService.getCpuUsagePercent()).thenReturn(10);
        when(metricsService.getUsedMemoryMb()).thenReturn(1024L);
    }

    @Test
    @DisplayName("404 from the control plane triggers re-registration")
    void unknownWorkerReRegisters() {
        doThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                HttpHeaders.EMPTY, new byte[0], null))
                .when(client).sendHeartbeat(any());

        scheduler.sendHeartbeat();

        verify(workerStartup).markUnregistered();
    }

    @Test
    @DisplayName("transient failures keep the registration")
    void transientFailuresKeepRegistration() {
        doThrow(new ResourceAccessException("timeout"))
                .doThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Unavailable",
                        HttpHeaders.EMPTY, new byte[0], null))
                .when(client).sendHeartbeat(any());

        scheduler.sendHeartbeat();
        scheduler.sendHeartbeat();

        verify(workerStartup, never()).markUnregistered();
    }
}
