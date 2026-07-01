package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.dto.UserResponse;
import com.bic.cloud.controlplane.exception.IllegalUserOperationException;
import com.bic.cloud.controlplane.exception.InvalidRoleException;
import com.bic.cloud.controlplane.exception.UserHasProjectsException;
import com.bic.cloud.controlplane.model.BicloudUser;
import com.bic.cloud.controlplane.repository.BicloudUserRepository;
import com.bic.cloud.controlplane.repository.UserProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    static final String ROLE_ADMIN = "ADMIN";
    static final String ROLE_USER = "USER";
    private static final Set<String> VALID_ROLES = Set.of(ROLE_ADMIN, ROLE_USER);

    private final BicloudUserRepository userRepository;
    private final UserProjectRepository userProjectRepository;

    @Transactional(readOnly = true)
    public List<UserResponse> listAllUsers() {
        return userRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public UserResponse getUserById(UUID userId) {
        BicloudUser user = findOrThrow(userId);
        return toResponse(user);
    }

    /**
     * Role change. Guards:
     *  - role can only be {ADMIN, USER} (service-level defense).
     *  - an admin cannot change their own role (prevents self-lockout).
     *  - the last ADMIN cannot be demoted (system must keep an admin).
     */
    @Transactional
    public UserResponse changeRole(UUID userId, String newRole, UUID callerId) {
        if (newRole == null || !VALID_ROLES.contains(newRole)) {
            throw new InvalidRoleException(newRole);
        }

        BicloudUser user = findOrThrow(userId);

        if (user.getId().equals(callerId)) {
            throw new IllegalUserOperationException("You cannot change your own role.");
        }

        String oldRole = user.getRole();

        boolean demotingAdmin = ROLE_ADMIN.equals(oldRole) && !ROLE_ADMIN.equals(newRole);
        if (demotingAdmin && isLastAdmin()) {
            throw new IllegalUserOperationException(
                    "The last ADMIN cannot be demoted. Assign another ADMIN first.");
        }

        user.setRole(newRole);
        userRepository.save(user);
        log.info("User role changed: userId={}, oldRole={}, newRole={}", userId, oldRole, newRole);
        return toResponse(user);
    }

    /**
     * User deletion. Guards:
     *  - an admin cannot delete their own account.
     *  - Son ADMIN silinemez.
     *  - a user who owns projects cannot be deleted (meaningful 409 instead of an FK violation / 500).
     */
    @Transactional
    public void deleteUser(UUID userId, UUID callerId) {
        BicloudUser user = findOrThrow(userId);

        if (user.getId().equals(callerId)) {
            throw new IllegalUserOperationException("You cannot delete your own account.");
        }

        if (ROLE_ADMIN.equals(user.getRole()) && isLastAdmin()) {
            throw new IllegalUserOperationException(
                    "The last ADMIN cannot be deleted. Assign another ADMIN first.");
        }

        long ownedProjects = userProjectRepository.countByOwner_Id(userId);
        if (ownedProjects > 0) {
            throw new UserHasProjectsException(ownedProjects);
        }

        userRepository.delete(user);
        log.info("User deleted by admin: userId={}, username={}", userId, user.getUsername());
    }

    /** True when the system has 1 or fewer ADMINs left. */
    private boolean isLastAdmin() {
        return userRepository.countByRole(ROLE_ADMIN) <= 1;
    }

    private BicloudUser findOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + userId));
    }

    private UserResponse toResponse(BicloudUser user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getRole(), user.getCreatedAt());
    }
}
