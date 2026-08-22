package com.signallab.api.global.config;

public enum AuthMode {
    DISABLED,
    MOCK,
    SUPABASE;

    public static AuthMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return MOCK;
        }
        return switch (raw.trim().toLowerCase()) {
            case "disabled" -> DISABLED;
            case "mock" -> MOCK;
            case "supabase" -> SUPABASE;
            default -> throw new IllegalArgumentException("AUTH_MODE must be disabled, mock, or supabase; received " + raw);
        };
    }
}
