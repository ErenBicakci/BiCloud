package com.bic.cloud.controlplane.exception;

public class InvalidWorkerIpException extends BaseException {

    public InvalidWorkerIpException(String ipAddress) {
        super("INVALID_WORKER_IP",
                "Worker advertised an invalid IP address: " + (ipAddress == null ? "<empty>" : ipAddress));
    }
}
