package com.signallab.api.global.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ApiEnvelope {

    private ApiEnvelope() {
    }

    public static Map<String, Object> ok(Object data, boolean mock) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("requestId", UUID.randomUUID().toString());
        meta.put("generatedAt", Instant.now().toString());
        meta.put("mock", mock);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("data", data);
        envelope.put("meta", meta);
        return envelope;
    }
}
