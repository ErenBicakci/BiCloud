package com.bic.cloud.controlplane.security;

import com.bic.cloud.controlplane.controller.WorkerNodeController;
import com.bic.cloud.controlplane.service.ContainerMetricsService;
import com.bic.cloud.controlplane.service.ContainerReconciliationService;
import com.bic.cloud.controlplane.service.GatewayNotificationService;
import com.bic.cloud.controlplane.service.ServiceDiscoveryService;
import com.bic.cloud.controlplane.service.WorkerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WorkerNodeController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
@TestPropertySource(properties = "bicloud.api-key=test-api-key")
class InternalApiSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkerService workerService;

    @MockBean
    private ServiceDiscoveryService serviceDiscoveryService;

    @MockBean
    private ContainerReconciliationService reconciliationService;

    @MockBean
    private ContainerMetricsService containerMetricsService;

    @MockBean
    private GatewayNotificationService gatewayNotificationService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @Test
    @DisplayName("internal API without a key -> 401")
    void missingKeyIsRejected() throws Exception {
        mockMvc.perform(get("/api/workers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verify(workerService, never()).listAllWorkers();
    }

    @Test
    @DisplayName("internal API with a wrong key -> 401")
    void wrongKeyIsRejected() throws Exception {
        mockMvc.perform(get("/api/workers").header("X-Api-Key", "wrong-key"))
                .andExpect(status().isUnauthorized());

        verify(workerService, never()).listAllWorkers();
    }

    @Test
    @DisplayName("internal API with the shared key -> allowed")
    void validKeyIsAccepted() throws Exception {
        when(workerService.listAllWorkers()).thenReturn(List.of());

        mockMvc.perform(get("/api/workers").header("X-Api-Key", "test-api-key"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("a user session does not grant access to the internal API")
    void userSessionIsNotEnough() throws Exception {
        mockMvc.perform(get("/api/workers"))
                .andExpect(status().isForbidden());

        verify(workerService, never()).listAllWorkers();
    }
}
