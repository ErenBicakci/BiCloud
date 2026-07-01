package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.model.AuditEvent;
import com.bic.cloud.controlplane.model.AuditEvent.*;
import com.bic.cloud.controlplane.model.UserProject;
import com.bic.cloud.controlplane.repository.AuditEventRepository;
import com.bic.cloud.controlplane.security.BicloudUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Records audit events.
 *
 * Design note: writes happen in the caller's current transaction. This is
 * deliberate - if the main operation rolls back, so does the audit record,
 * so an action that never happened is never logged. record(...) still never
 * leaks an exception to the caller; audit must never break the main flow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository auditEventRepository;

    // User events

    public void userAction(BicloudUserDetails caller, AuditAction action,
                           TargetType targetType, String targetName,
                           UserProject project, String message) {
        record(AuditEvent.builder()
                .actorType(ActorType.USER)
                .actorName(caller != null ? caller.getUsername() : "unknown")
                .action(action)
                .targetType(targetType)
                .targetName(targetName)
                .projectId(project != null ? project.getId() : null)
                .ownerName(project != null && project.getOwner() != null
                        ? project.getOwner().getUsername() : null)
                .severity(Severity.INFO)
                .message(message)
                .build());
    }

    // System events (project scoped)

    public void systemAction(String component, AuditAction action, Severity severity,
                            TargetType targetType, String targetName,
                            Long projectId, String ownerName, String message) {
        record(AuditEvent.builder()
                .actorType(ActorType.SYSTEM)
                .actorName(component)
                .action(action)
                .targetType(targetType)
                .targetName(targetName)
                .projectId(projectId)
                .ownerName(ownerName)
                .severity(severity)
                .message(message)
                .build());
    }

    // System events (worker level - admin-only, ownerName null)

    public void workerAction(String component, AuditAction action, Severity severity,
                            String workerName, String message) {
        record(AuditEvent.builder()
                .actorType(ActorType.SYSTEM)
                .actorName(component)
                .action(action)
                .targetType(TargetType.WORKER)
                .targetName(workerName)
                .severity(severity)
                .message(message)
                .build());
    }

    private void record(AuditEvent event) {
        try {
            auditEventRepository.save(event);
        } catch (Exception e) {
            // audit must never break the main operation - just log.
            log.warn("Audit event kaydedilemedi (action={}): {}", event.getAction(), e.getMessage());
        }
    }
}
