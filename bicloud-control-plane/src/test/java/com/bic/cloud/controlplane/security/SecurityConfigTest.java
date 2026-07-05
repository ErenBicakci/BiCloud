package com.bic.cloud.controlplane.security;

import com.bic.cloud.controlplane.controller.AuthController;
import com.bic.cloud.controlplane.controller.WorkerViewController;
import com.bic.cloud.controlplane.dto.auth.AuthResponse;
import com.bic.cloud.controlplane.dto.auth.LoginRequest;
import com.bic.cloud.controlplane.dto.auth.RegisterRequest;
import com.bic.cloud.controlplane.service.AuthService;
import com.bic.cloud.controlplane.service.WorkerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        AuthController.class,
        WorkerViewController.class,
        SecurityConfigTest.TestActuatorController.class
})
@Import({SecurityConfig.class, JwtAuthFilter.class})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private WorkerService workerService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @Test
    @DisplayName("POST /auth/register remains public")
    void registerRemainsPublic() throws Exception {
        when(authService.register(any(RegisterRequest.class)))
                .thenReturn(new AuthResponse("token", "alice", "USER", 86_400_000));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("token"));
    }

    @Test
    @DisplayName("POST /auth/login remains public")
    void loginRemainsPublic() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenReturn(new AuthResponse("token", "alice", "USER", 86_400_000));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("token"));
    }

    @Test
    @DisplayName("GET /auth/me requires authentication")
    void meRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verify(authService, never()).getMe(any());
    }

    @Test
    @DisplayName("POST /auth/admin/register requires authentication")
    void adminRegisterRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/auth/admin/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"root\",\"password\":\"password123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verify(authService, never()).registerAdmin(any());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("POST /auth/admin/register rejects non-admin users")
    void adminRegisterRejectsNonAdminUsers() throws Exception {
        mockMvc.perform(post("/auth/admin/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"root\",\"password\":\"password123\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(authService, never()).registerAdmin(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /auth/admin/register allows admins")
    void adminRegisterAllowsAdmins() throws Exception {
        when(authService.registerAdmin(any(RegisterRequest.class)))
                .thenReturn(new AuthResponse("admin-token", "root", "ADMIN", 86_400_000));

        mockMvc.perform(post("/auth/admin/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"root\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    @WithMockUser
    @DisplayName("Unknown /auth/** endpoints are denied instead of falling through")
    void unknownAuthEndpointsAreDenied() throws Exception {
        mockMvc.perform(get("/auth/debug"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("GET /workers requires authentication")
    void workersRequireAuthentication() throws Exception {
        mockMvc.perform(get("/workers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verify(workerService, never()).listAllWorkers();
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("GET /workers rejects non-admin users")
    void workersRejectNonAdminUsers() throws Exception {
        mockMvc.perform(get("/workers"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(workerService, never()).listAllWorkers();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /workers allows admins")
    void workersAllowAdmins() throws Exception {
        when(workerService.listAllWorkers()).thenReturn(List.of());

        mockMvc.perform(get("/workers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Actuator health remains public")
    void actuatorHealthRemainsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("Sensitive actuator endpoints require authentication")
    void actuatorEnvRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Sensitive actuator endpoints allow admins")
    void actuatorEnvAllowsAdmins() throws Exception {
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propertySources").isArray());
    }

    @RestController
    static class TestActuatorController {

        @GetMapping("/actuator/health")
        Map<String, String> health() {
            return Map.of("status", "UP");
        }

        @GetMapping("/actuator/env")
        Map<String, Object> env() {
            return Map.of("propertySources", java.util.List.of());
        }
    }
}
