package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.model.AuditEvent;
import com.bic.cloud.controlplane.model.AuditEvent.*;
import com.bic.cloud.controlplane.model.ProjectImage;
import com.bic.cloud.controlplane.model.UserProject;
import com.bic.cloud.controlplane.repository.AuditEventRepository;
import com.bic.cloud.controlplane.security.BicloudUserDetails;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;
    private final TransactionTemplate separateTransaction;

    public AuditService(AuditEventRepository auditEventRepository, PlatformTransactionManager transactionManager) {
        this.auditEventRepository = auditEventRepository;
        this.separateTransaction = new TransactionTemplate(transactionManager);
        this.separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

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
                .ownerName(ownerName(project))
                .severity(Severity.INFO)
                .message(message)
                .build());
    }

    public void serviceEvent(String component, AuditAction action, Severity severity,
                             TargetType targetType, ProjectImage image, String message) {
        UserProject project = image.getProject();
        record(AuditEvent.builder()
                .actorType(ActorType.SYSTEM)
                .actorName(component)
                .action(action)
                .targetType(targetType)
                .targetName(image.getServiceName())
                .projectId(project != null ? project.getId() : null)
                .ownerName(ownerName(project))
                .severity(severity)
                .message(message)
                .build());
    }

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

    private String ownerName(UserProject project) {
        try {
            return project != null && project.getOwner() != null ? project.getOwner().getUsername() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // a failed audit insert must not roll back the operation being audited
    private void record(AuditEvent event) {
        try {
            separateTransaction.executeWithoutResult(status -> auditEventRepository.save(event));
        } catch (Exception e) {
            log.warn("Failed to record audit event (action={}): {}", event.getAction(), e.getMessage());
        }
    }
}
