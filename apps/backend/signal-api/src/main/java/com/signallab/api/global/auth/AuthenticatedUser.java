package com.signallab.api.global.auth;

import java.util.Map;

public record AuthenticatedUser(String userId) {
    public static final String REQUEST_ATTRIBUTE = "signal.authenticatedUser";

    public static AuthenticatedUser fromRequestAttribute(Object value) {
        if (value instanceof AuthenticatedUser user) {
            return user;
        }
        throw new IllegalStateException("Authenticated user is missing from request context.");
    }
}
