package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.dto.UserResponse;
import com.bic.cloud.controlplane.dto.auth.AuthResponse;
import com.bic.cloud.controlplane.dto.auth.LoginRequest;
import com.bic.cloud.controlplane.dto.auth.RegisterRequest;
import com.bic.cloud.controlplane.exception.UserAlreadyExistsException;
import com.bic.cloud.controlplane.model.BicloudUser;
import com.bic.cloud.controlplane.repository.BicloudUserRepository;
import com.bic.cloud.controlplane.security.BicloudUserDetails;
import com.bic.cloud.controlplane.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final BicloudUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;

    public AuthResponse register(RegisterRequest request) {
        return registerWithRole(request, "USER");
    }

    public AuthResponse registerAdmin(RegisterRequest request) {
        return registerWithRole(request, "ADMIN");
    }

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));

        BicloudUser user = userRepository.findByUsername(request.username())
                .orElseThrow();

        String token = jwtUtil.generateToken(user.getUsername(), user.getRole(), user.getId());
        log.info("User logged in: {}", request.username());

        return new AuthResponse(token, user.getUsername(), user.getRole(), jwtUtil.getExpiration());
    }

    public UserResponse getMe(BicloudUserDetails caller) {
        if (caller == null) {
            throw new AccessDeniedException("Authentication required");
        }

        BicloudUser user = userRepository.findById(caller.getId())
                .orElseThrow();
        return new UserResponse(user.getId(), user.getUsername(), user.getRole(), user.getCreatedAt());
    }

    private AuthResponse registerWithRole(RegisterRequest request, String role) {
        if (userRepository.existsByUsername(request.username())) {
            throw new UserAlreadyExistsException(request.username());
        }

        BicloudUser user = BicloudUser.builder()
                .username(request.username())
                .password(passwordEncoder.encode(request.password()))
                .role(role)
                .build();

        userRepository.save(user);
        log.info("New user registered: {} (role={})", request.username(), role);

        String token = jwtUtil.generateToken(user.getUsername(), user.getRole(), user.getId());
        return new AuthResponse(token, user.getUsername(), user.getRole(), jwtUtil.getExpiration());
    }
}
