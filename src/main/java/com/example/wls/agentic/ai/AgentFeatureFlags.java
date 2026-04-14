package com.example.wls.agentic.ai;

import io.helidon.config.Config;

final class AgentFeatureFlags {

    private AgentFeatureFlags() {
    }

    static boolean isEnabled(RequestIntent intent) {
        if (intent == null) {
            return false;
        }

        String key = switch (intent) {
            case DOMAIN_VIEW -> "agent-features.domain-runtime.enabled";
            case PATCHING -> "agent-features.patching.enabled";
            case APP_MANAGEMENT -> "agent-features.app-management.enabled";
            case DIAGNOSTIC_TROUBLESHOOTING -> "agent-features.diagnostic.enabled";
        };

        return Config.global().get(key).asBoolean().orElse(true);
    }
}
