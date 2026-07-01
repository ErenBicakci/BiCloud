package com.bic.cloud.worker.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class WorkerExceptionHandler {

    @ExceptionHandler(DockerOperationException.class)
    public ResponseEntity<Map<String, Object>> handleDockerOperation(DockerOperationException ex) {

        log.error("Docker operation failed: {}", ex.getMessage(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "code", "DOCKER_OPERATION_FAILED",
                        "message", ex.getMessage(),
                        "containerId", ex.getContainerId(),
                        "timestamp", Instant.now().toString()
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {

        log.error("Unexpected error", ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "code", "INTERNAL_ERROR",
                        "message", "Unexpected server error",
                        "timestamp", Instant.now().toString()
                ));
    }
}
