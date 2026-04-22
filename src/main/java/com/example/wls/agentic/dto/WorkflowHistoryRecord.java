package com.example.wls.agentic.dto;

import io.helidon.json.binding.Json;

@Json.Entity
public record WorkflowHistoryRecord(
        String domain,
        String workflowType,
        String operationType,
        String workflowStep,
        String workflowStatus,
        String lastUserRequest,
        String lastAssistantMessage,
        String updatedAt,
        Boolean terminal) {
}