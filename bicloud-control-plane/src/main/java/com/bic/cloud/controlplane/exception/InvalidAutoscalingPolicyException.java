package com.bic.cloud.controlplane.exception;

public class InvalidAutoscalingPolicyException extends BaseException {

    public InvalidAutoscalingPolicyException(String message) {
        super("INVALID_AUTOSCALING_POLICY", message);
    }
}
