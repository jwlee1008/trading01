package com.signallab.api.global.config;

public enum DataStoreMode {
    MOCK,
    POSTGRES;

    public static DataStoreMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return MOCK;
        }
        return switch (raw.trim().toLowerCase()) {
            case "mock" -> MOCK;
            case "postgres" -> POSTGRES;
            default -> throw new IllegalArgumentException("DATA_STORE must be mock or postgres; received " + raw);
        };
    }
}
