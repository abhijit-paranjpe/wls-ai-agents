package com.oracle.wls.agentic.workflow;

import io.helidon.json.binding.Json;

import java.time.Instant;

@Json.Entity
public record WorkflowSummary(
        String workflowId,
        String domain,
        WorkflowStatus currentState,
        Instant createdAt,
        Instant updatedAt,
        String conversationId,
        String taskId,
        String requestSummary) {
}
