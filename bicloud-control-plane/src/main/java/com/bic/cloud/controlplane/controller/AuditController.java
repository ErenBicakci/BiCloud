package com.bic.cloud.controlplane.controller;

import com.bic.cloud.controlplane.dto.AuditEventResponse;
import com.bic.cloud.controlplane.model.AuditEvent;
import com.bic.cloud.controlplane.model.UserProject;
import com.bic.cloud.controlplane.repository.AuditEventRepository;
import com.bic.cloud.controlplane.security.BicloudUserDetails;
import com.bic.cloud.controlplane.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/audit")
public class AuditController {

    private static final int MAX_LIMIT = 100;

    private final AuditEventRepository auditEventRepository;
    private final ProjectService projectService;

    /**
     * Audit feed. Admins see every event; regular users only see events of
     * their own projects. Cursor: with beforeId, returns events older than that id.
     */
    @GetMapping
    public ResponseEntity<List<AuditEventResponse>> feed(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @AuthenticationPrincipal BicloudUserDetails caller) {

        String ownerScope = isAdmin(caller) ? null : caller.getUsername();
        return ResponseEntity.ok(query(ownerScope, null, action, beforeId, limit));
    }

    /**
     * Audit feed of a single project. Caller must be the owner or an admin.
     */
    @GetMapping("/project/{projectId}")
    public ResponseEntity<List<AuditEventResponse>> projectFeed(
            @PathVariable Long projectId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @AuthenticationPrincipal BicloudUserDetails caller) {

        UserProject project = projectService.findById(projectId);
        projectService.assertOwnerOrAdmin(project, caller);

        // access verified; return all events of this project (no ownerScope needed).
        return ResponseEntity.ok(query(null, projectId, action, beforeId, limit));
    }

    private List<AuditEventResponse> query(String ownerScope, Long projectId,
                                           String actionRaw, Long beforeId, int limit) {
        AuditEvent.AuditAction action = parseAction(actionRaw);
        int safeLimit = Math.min(Math.max(1, limit), MAX_LIMIT);

        return auditEventRepository
                .findFeed(ownerScope, projectId, action, beforeId, PageRequest.of(0, safeLimit))
                .stream()
                .map(AuditEventResponse::from)
                .toList();
    }

    private AuditEvent.AuditAction parseAction(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("ALL")) return null;
        try {
            return AuditEvent.AuditAction.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null; // unknown filter -> all
        }
    }

    private boolean isAdmin(BicloudUserDetails caller) {
        return caller.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
