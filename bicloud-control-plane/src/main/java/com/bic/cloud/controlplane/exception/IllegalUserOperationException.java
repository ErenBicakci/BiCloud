package com.bic.cloud.controlplane.exception;

/**
 * Cases where an admin operation would leave the system inconsistent/locked:
 * an admin acting on their own account, or demoting/deleting the last ADMIN.
 */
public class IllegalUserOperationException extends BaseException {

    public IllegalUserOperationException(String message) {
        super("ILLEGAL_USER_OPERATION", message);
    }
}
