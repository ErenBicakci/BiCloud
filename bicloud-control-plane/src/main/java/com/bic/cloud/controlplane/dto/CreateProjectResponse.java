package com.bic.cloud.controlplane.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class CreateProjectResponse {

    private Long id;
    private String name;
    private String ownerUsername;
    private Instant createdAt;
}
