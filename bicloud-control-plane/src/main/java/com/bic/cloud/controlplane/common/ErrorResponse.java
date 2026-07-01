package com.bic.cloud.controlplane.common;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
public class ErrorResponse {

    private String code;
    private String message;
    private Instant timestamp;
}
