package com.signallab.api.domain.entitlement.service;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class EntitlementService {

    private static final List<String> MVP_FEATURES = List.of(
        "strategy.create", "signal.read", "paper.trade", "ranking.read", "profile.publish"
    );

    public Entitlements forUser(String userId) {
        return new Entitlements(
            "MVP_FREE",
            MVP_FEATURES.stream().map(feature -> new Decision(feature, true, "MVP_FREE")).toList()
        );
    }

    public record Entitlements(String plan, List<Decision> decisions) {}
    public record Decision(String feature, boolean allowed, String source) {}
}
