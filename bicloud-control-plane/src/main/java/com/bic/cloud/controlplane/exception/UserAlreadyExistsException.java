package com.bic.cloud.controlplane.exception;

public class UserAlreadyExistsException extends BaseException {

    public UserAlreadyExistsException(String username) {
        super("USER_ALREADY_EXISTS", "User already exists: " + username);
    }
}
