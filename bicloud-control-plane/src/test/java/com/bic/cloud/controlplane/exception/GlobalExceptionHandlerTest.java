package com.bic.cloud.controlplane.exception;

import com.bic.cloud.controlplane.controller.AuthController;
import com.bic.cloud.controlplane.controller.UserAdminController;
import com.bic.cloud.controlplane.controller.WorkerViewController;
import com.bic.cloud.controlplane.dto.auth.LoginRequest;
import com.bic.cloud.controlplane.security.JwtAuthFilter;
import com.bic.cloud.controlplane.security.JwtUtil;
import com.bic.cloud.controlplane.security.SecurityConfig;
import com.bic.cloud.controlplane.security.UserDetailsServiceImpl;
import com.bic.cloud.controlplane.service.AuthService;
import com.bic.cloud.controlplane.service.UserAdminService;
import com.bic.cloud.controlplane.service.WorkerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        AuthController.class,
        WorkerViewController.class,
        UserAdminController.class
})
@Import({SecurityConfig.class, JwtAuthFilter.class})
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private WorkerService workerService;

    @MockBean
    private UserAdminService userAdminService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @Test
    @DisplayName("Wrong credentials -> 401 INVALID_CREDENTIALS")
    void badCredentialsReturnUnauthorized() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("Malformed JSON body -> 400 MALFORMED_REQUEST")
    void malformedJsonReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("Register with a password over 72 bytes -> 400 VALIDATION_ERROR, service not called")
    void registerRejectsPasswordOverBcryptLimit() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"" + "a".repeat(73) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(authService, never()).register(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Path variable that is not a UUID -> 400 INVALID_PARAMETER")
    void invalidUuidReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/workers/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Missing required request parameter -> 400 MISSING_PARAMETER")
    void missingParameterReturnsBadRequest() throws Exception {
        mockMvc.perform(put("/workers/" + UUID.randomUUID() + "/maintenance"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Unknown user id -> 404 USER_NOT_FOUND")
    void unknownUserReturnsNotFound() throws Exception {
        UUID userId = UUID.randomUUID();
        when(userAdminService.getUserById(userId)).thenThrow(new UserNotFoundException(userId));

        mockMvc.perform(get("/admin/users/" + userId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Unsupported HTTP method -> 405 METHOD_NOT_ALLOWED")
    void unsupportedMethodReturnsMethodNotAllowed() throws Exception {
        mockMvc.perform(delete("/workers"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Unknown path -> 404 NOT_FOUND")
    void unknownPathReturnsNotFound() throws Exception {
        mockMvc.perform(get("/no-such-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
