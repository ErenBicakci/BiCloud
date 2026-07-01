package com.bic.cloud.controlplane.exception;

import lombok.Getter;

@Getter
public abstract class BaseException extends RuntimeException {

    private final String code;

    protected BaseException(String code, String message) {
        super(message);
        this.code = code;
    }
}
