package com.bic.cloud.controlplane.exception;

import java.util.UUID;

public class WorkerNotFoundException extends BaseException {

    public WorkerNotFoundException(String workerName) {
        super("WORKER_NOT_FOUND",
                "Worker not found: " + workerName);
    }

    public WorkerNotFoundException(UUID workerId) {
        super("WORKER_NOT_FOUND",
                "Worker not found with id: " + workerId);
    }
}
