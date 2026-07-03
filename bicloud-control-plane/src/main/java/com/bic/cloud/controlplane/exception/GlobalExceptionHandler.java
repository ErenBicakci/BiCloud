package com.bic.cloud.controlplane.exception;

import com.bic.cloud.controlplane.common.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ErrorResponse> handleBaseException(BaseException ex) {

        HttpStatus status = resolveStatus(ex.getCode());

        log.warn("Business error [{}]: {}", ex.getCode(), ex.getMessage());

        return ResponseEntity.status(status)
                .body(ErrorResponse.builder()
                        .code(ex.getCode())
                        .message(ex.getMessage())
                        .timestamp(Instant.now())
                        .build());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.builder()
                        .code("FORBIDDEN")
                        .message("You do not have permission to access this resource")
                        .timestamp(Instant.now())
                        .build());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {

        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.builder()
                        .code("VALIDATION_ERROR")
                        .message(errors)
                        .timestamp(Instant.now())
                        .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {

        log.error("Unexpected error", ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.builder()
                        .code("INTERNAL_ERROR")
                        .message("Unexpected server error")
                        .timestamp(Instant.now())
                        .build());
    }

    private HttpStatus resolveStatus(String code) {
        return switch (code) {
            case "PROJECT_NOT_FOUND",
                 "IMAGE_NOT_FOUND",
                 "WORKER_NOT_FOUND",
                 "CONTAINER_INSTANCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;

            case "FORBIDDEN" -> HttpStatus.FORBIDDEN;

            case "NO_AVAILABLE_WORKER" -> HttpStatus.SERVICE_UNAVAILABLE;

            case "WORKER_COMMUNICATION_FAILED" -> HttpStatus.BAD_GATEWAY;

            case "NO_IMAGES_CONFIGURED" -> HttpStatus.UNPROCESSABLE_ENTITY;

            case "USER_ALREADY_EXISTS",
                 "USER_HAS_PROJECTS",
                 "NAME_CONFLICT",
                 "ILLEGAL_USER_OPERATION" -> HttpStatus.CONFLICT;

            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
