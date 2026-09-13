package com.bic.cloud.controlplane.exception;

import java.util.UUID;

public class UserNotFoundException extends BaseException {

    public UserNotFoundException(String username) {
        super("USER_NOT_FOUND",
                "User not found: " + username);
    }

    public UserNotFoundException(UUID userId) {
        super("USER_NOT_FOUND",
                "User not found with id: " + userId);
    }
}
