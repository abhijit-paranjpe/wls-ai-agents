package com.oracle.wls.agentic.workflow;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WorkflowResponseContract {

    private static final Pattern STATUS_PATTERN = Pattern.compile("(?i)\"status\"\\s*:\\s*\"([a-z-]+)\"");
    private static final Pattern HOST_PIDS_PATTERN = Pattern.compile("(?i)\"hostPids\"\\s*:\\s*\\{([^}]*)}");

    private WorkflowResponseContract() {
    }

    public static String extractStatus(String value) {
        if (value == null || value.isBlank()) {
            return "running";
        }
        Matcher jsonMatcher = STATUS_PATTERN.matcher(value);
        if (jsonMatcher.find()) {
            return jsonMatcher.group(1).trim().toLowerCase(Locale.ROOT);
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("not found")) {
            return "not found";
        }
        if (lower.contains("failed")) {
            return "failed";
        }
        if (lower.contains("completed")) {
            return "completed";
        }
        return "running";
    }

    public static boolean isFailed(String value) {
        return "failed".equals(extractStatus(value));
    }

    public static boolean hasTrackingContext(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        Matcher hostPidsMatcher = HOST_PIDS_PATTERN.matcher(value);
        boolean hasHostPids = hostPidsMatcher.find()
                && hostPidsMatcher.group(1) != null
                && !hostPidsMatcher.group(1).isBlank();
        boolean hasTrackingObject = value.toLowerCase(Locale.ROOT).contains("\"tracking\"")
                || value.toLowerCase(Locale.ROOT).contains("tracking:");
        if (!hasHostPids && !hasTrackingObject) {
            return false;
        }

        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("\"domain\"")
                || normalized.contains("domain:")
                || normalized.contains("targetdomain:");
    }

    public static String trackingContextMissingFailureResponse(String domain, String stepName) {
        String safeDomain = domain == null ? "" : domain;
        String safeStepName = stepName == null ? "monitor-step" : stepName;
        return """
                {"status":"failed","operation":"track-async-job","domain":"%s","message":"Missing required async tracking context (hostPids/domain) in lastResponse for step %s"}
                """.formatted(safeDomain, safeStepName);
    }

    public static String composePromptWithWorkflowContext(String stepQuestion,
                                                          String instruction,
                                                          String targetDomain,
                                                          String lastResponse) {
        return """
                %s

                Workflow instruction: %s
                targetDomain: %s
                lastResponse: %s
                """.formatted(nullToEmpty(stepQuestion), nullToEmpty(instruction), nullToEmpty(targetDomain), nullToEmpty(lastResponse));
    }

    public static String composePromptWithMonitoringContext(String stepQuestion,
                                                            String targetDomain,
                                                            String lastResponse) {
        return """
                %s

                targetDomain: %s
                lastResponse: %s
                """.formatted(nullToEmpty(stepQuestion), nullToEmpty(targetDomain), nullToEmpty(lastResponse));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
