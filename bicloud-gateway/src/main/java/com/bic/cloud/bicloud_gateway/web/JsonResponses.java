package com.bic.cloud.bicloud_gateway.web;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

public final class JsonResponses {

    private JsonResponses() {
    }

    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String json) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    public static Mono<Void> error(ServerWebExchange exchange, HttpStatus status, String error, String message) {
        return write(exchange, status, """
                {"error":"%s","status":%d,"message":"%s"}""".formatted(error, status.value(), message));
    }
}
