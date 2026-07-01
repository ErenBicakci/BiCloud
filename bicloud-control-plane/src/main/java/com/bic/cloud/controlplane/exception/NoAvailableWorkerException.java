package com.bic.cloud.controlplane.exception;

public class NoAvailableWorkerException extends BaseException {

    public NoAvailableWorkerException() {
        super("NO_AVAILABLE_WORKER",
                "No active worker with sufficient capacity is available for deployment.");
    }

    public NoAvailableWorkerException(String detail) {
        super("NO_AVAILABLE_WORKER",
                "No available worker: " + detail);
    }
}
