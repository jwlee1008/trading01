package com.signallab.worker.global.config;

/** Reads an operating-system environment variable first, then a dotenv-backed system property. */
public final class RuntimeEnvironment {
    private RuntimeEnvironment() {}

    public static String get(String key) {
        String environmentValue = System.getenv(key);
        if (environmentValue != null && !environmentValue.isBlank()) return environmentValue;
        return System.getProperty(key);
    }
}
