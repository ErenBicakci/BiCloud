package com.bic.cloud.bicloud_gateway.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;


@Component
@Order(-2)
@Slf4j
public class GlobalErrorHandler implements ErrorWebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        HttpStatus status  = resolveStatus(ex);
        String     message = resolveMessage(ex, status);
        String     path    = exchange.getRequest().getPath().value();

        if (status.is5xxServerError()) {
            log.error("Gateway 5xx error [{}] {} - {}", status.value(), path, ex.getMessage(), ex);
        } else {
            log.warn("Gateway error [{}] {} - {}", status.value(), path, ex.getMessage());
        }

        String json = """
                {"timestamp":"%s","status":%d,"error":"%s","message":"%s","path":"%s"}"""
                .formatted(
                        Instant.now().toString(),
                        status.value(),
                        status.getReasonPhrase(),
                        sanitize(message),
                        path
                );

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buf = exchange.getResponse().bufferFactory()
                .wrap(json.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buf));
    }

    private HttpStatus resolveStatus(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            return HttpStatus.valueOf(rse.getStatusCode().value());
        }
        // upstream connection error: container is down or unreachable
        if (ex instanceof ConnectException
                || ex.getCause() instanceof ConnectException) {
            return HttpStatus.BAD_GATEWAY;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private String resolveMessage(Throwable ex, HttpStatus status) {
        if (ex instanceof ResponseStatusException rse && rse.getReason() != null) {
            return rse.getReason();
        }
        if (status == HttpStatus.BAD_GATEWAY) {
            return "The target service is currently unavailable. Please try again.";
        }
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            return "An unexpected server error occurred.";
        }
        return ex.getMessage() != null ? ex.getMessage() : status.getReasonPhrase();
    }

    /**
     * Escapes problematic characters inside a JSON string.
     * Prevents malicious exception messages from breaking the JSON.
     */
    private String sanitize(String msg) {
        if (msg == null) return "";
        return msg.replace("\"", "'").replace("\n", " ").replace("\r", "");
    }
}
