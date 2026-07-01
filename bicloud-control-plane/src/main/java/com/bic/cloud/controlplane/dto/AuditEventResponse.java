package com.bic.cloud.controlplane.dto;

import com.bic.cloud.controlplane.model.AuditEvent;
import lombok.*;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEventResponse {

    private Long id;
    private Instant createdAt;
    private String actorType;
    private String actorName;
    private String action;
    private String targetType;
    private String targetName;
    private Long projectId;
    private String severity;
    private String message;

    public static AuditEventResponse from(AuditEvent e) {
        return AuditEventResponse.builder()
                .id(e.getId())
                .createdAt(e.getCreatedAt())
                .actorType(e.getActorType() != null ? e.getActorType().name() : null)
                .actorName(e.getActorName())
                .action(e.getAction() != null ? e.getAction().name() : null)
                .targetType(e.getTargetType() != null ? e.getTargetType().name() : null)
                .targetName(e.getTargetName())
                .projectId(e.getProjectId())
                .severity(e.getSeverity() != null ? e.getSeverity().name() : null)
                .message(e.getMessage())
                .build();
    }
}
