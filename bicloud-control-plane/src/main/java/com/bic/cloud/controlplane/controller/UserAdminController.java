package com.bic.cloud.controlplane.controller;

import com.bic.cloud.controlplane.dto.ChangeRoleRequest;
import com.bic.cloud.controlplane.dto.UserResponse;
import com.bic.cloud.controlplane.security.BicloudUserDetails;
import com.bic.cloud.controlplane.service.UserAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {

    private final UserAdminService userAdminService;

    @GetMapping
    public ResponseEntity<List<UserResponse>> listAllUsers() {
        return ResponseEntity.ok(userAdminService.listAllUsers());
    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserResponse> getUser(@PathVariable UUID userId) {
        return ResponseEntity.ok(userAdminService.getUserById(userId));
    }

    @PutMapping("/{userId}/role")
    public ResponseEntity<UserResponse> changeRole(
            @PathVariable UUID userId,
            @Valid @RequestBody ChangeRoleRequest request,
            @AuthenticationPrincipal BicloudUserDetails caller) {

        return ResponseEntity.ok(userAdminService.changeRole(userId, request.role(), caller.getId()));
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> deleteUser(
            @PathVariable UUID userId,
            @AuthenticationPrincipal BicloudUserDetails caller) {

        userAdminService.deleteUser(userId, caller.getId());
        return ResponseEntity.noContent().build();
    }
}
