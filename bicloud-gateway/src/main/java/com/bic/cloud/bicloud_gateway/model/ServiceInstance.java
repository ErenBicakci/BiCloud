package com.bic.cloud.bicloud_gateway.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceInstance {

    private String instanceId;


    private String ip;

    private int port;

    @Builder.Default
    private Instant registeredAt = Instant.now();


    public String toUri() {
        return "http://" + ip + ":" + port;
    }


    public boolean isSameEndpoint(String ip, int port) {
        return this.ip.equals(ip) && this.port == port;
    }
}
