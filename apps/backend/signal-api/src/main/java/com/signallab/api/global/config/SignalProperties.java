package com.signallab.api.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "signal")
public class SignalProperties {

    private String authMode = "mock";
    private String dataStore = "mock";
    private String devAuthToken = "demo-token";
    private String devAuthUserId = "demo-user";
    private String devAuthTokenMap = "";
    private String supabaseUrl = "";
    private String corsOrigins = "";
    private String workerServiceToken = "";
    private String geminiApiKey = "";
    private String geminiModel = "gemini-3.6-flash";
    private String geminiBaseUrl = "https://generativelanguage.googleapis.com";

    public AuthMode resolvedAuthMode() {
        return AuthMode.from(authMode);
    }

    public DataStoreMode resolvedDataStore() {
        return DataStoreMode.from(dataStore);
    }

    public String normalizedSupabaseUrl() {
        if (supabaseUrl == null || supabaseUrl.isBlank()) {
            return "";
        }
        return supabaseUrl.replaceAll("/+$", "");
    }

    public String getAuthMode() {
        return authMode;
    }

    public void setAuthMode(String authMode) {
        this.authMode = authMode;
    }

    public String getDataStore() {
        return dataStore;
    }

    public void setDataStore(String dataStore) {
        this.dataStore = dataStore;
    }

    public String getDevAuthToken() {
        return devAuthToken;
    }

    public void setDevAuthToken(String devAuthToken) {
        this.devAuthToken = devAuthToken;
    }

    public String getDevAuthUserId() {
        return devAuthUserId;
    }

    public void setDevAuthUserId(String devAuthUserId) {
        this.devAuthUserId = devAuthUserId;
    }

    public String getDevAuthTokenMap() {
        return devAuthTokenMap;
    }

    public void setDevAuthTokenMap(String devAuthTokenMap) {
        this.devAuthTokenMap = devAuthTokenMap;
    }

    public String getSupabaseUrl() {
        return supabaseUrl;
    }

    public void setSupabaseUrl(String supabaseUrl) {
        this.supabaseUrl = supabaseUrl;
    }

    public String getCorsOrigins() {
        return corsOrigins;
    }

    public void setCorsOrigins(String corsOrigins) {
        this.corsOrigins = corsOrigins;
    }

    public String getWorkerServiceToken() {
        return workerServiceToken;
    }

    public void setWorkerServiceToken(String workerServiceToken) {
        this.workerServiceToken = workerServiceToken;
    }

    public String getGeminiApiKey() { return geminiApiKey; }
    public void setGeminiApiKey(String geminiApiKey) { this.geminiApiKey = geminiApiKey; }
    public String getGeminiModel() { return geminiModel; }
    public void setGeminiModel(String geminiModel) { this.geminiModel = geminiModel; }
    public String getGeminiBaseUrl() { return geminiBaseUrl; }
    public void setGeminiBaseUrl(String geminiBaseUrl) { this.geminiBaseUrl = geminiBaseUrl; }
}
