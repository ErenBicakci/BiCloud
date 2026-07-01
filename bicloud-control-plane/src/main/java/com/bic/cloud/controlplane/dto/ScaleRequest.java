package com.bic.cloud.controlplane.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class ScaleRequest {

    @Min(value = 0,  message = "replicas must be at least 0.")
    @Max(value = 10, message = "replicas may be at most 10.")
    private int replicas;
}
