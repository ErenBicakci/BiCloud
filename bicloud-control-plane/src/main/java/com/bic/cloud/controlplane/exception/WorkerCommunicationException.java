package com.bic.cloud.controlplane.exception;

public class WorkerCommunicationException extends BaseException {

    public WorkerCommunicationException(String workerName, String detail) {
        super("WORKER_COMMUNICATION_FAILED",
                "Communication with worker '" + workerName + "' failed: " + detail);
    }

    public WorkerCommunicationException(String workerName, String detail, Throwable cause) {
        super("WORKER_COMMUNICATION_FAILED",
                "Communication with worker '" + workerName + "' failed: " + detail);
        initCause(cause);
    }
}
