package com.bic.cloud.controlplane.exception;

public class IllegalUserOperationException extends BaseException {

    public IllegalUserOperationException(String message) {
        super("ILLEGAL_USER_OPERATION", message);
    }
}
