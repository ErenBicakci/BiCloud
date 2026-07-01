package com.bic.cloud.controlplane.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class CreateProjectResponse {

    private Long id;
    private String name;
    private String ownerUsername;
    private LocalDateTime createdAt;
}
