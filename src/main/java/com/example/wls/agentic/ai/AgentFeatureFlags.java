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
            case DOMAIN_CONFIGURATION -> "agent-features.domain-configuration.enabled";
            case DOMAIN_RUNTIME -> "agent-features.domain-runtime.enabled";
            case WORKFLOW_REQUEST -> "agent-features.workflow.enabled";
            case PATCHING -> "agent-features.patching.enabled";
            case APP_MANAGEMENT -> "agent-features.app-management.enabled";
            case DIAGNOSTIC_TROUBLESHOOTING -> "agent-features.diagnostic.enabled";
            case GENERAL_ASSISTANCE -> null;
        };

        if (key == null) {
            return true;
        }

        return Config.global().get(key).asBoolean().orElse(true);
    }
}
