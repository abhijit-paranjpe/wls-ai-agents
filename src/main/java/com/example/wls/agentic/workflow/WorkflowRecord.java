package com.example.wls.agentic.workflow;

import io.helidon.json.binding.Json;

import java.time.Instant;
import java.util.List;

@Json.Entity
public record WorkflowRecord(
        String workflowId,
        String domain,
        WorkflowStatus currentState,
        Instant createdAt,
        Instant updatedAt,
        String conversationId,
        String taskId,
        String requestSummary,
        ApprovalDecision approvalDecision,
        Instant approvalDecisionAt,
        WorkflowChannel approvalChannel,
        String failureReason,
        List<WorkflowStepRecord> steps) {

    public WorkflowSummary toSummary() {
        return new WorkflowSummary(
                workflowId,
                domain,
                currentState,
                createdAt,
                updatedAt,
                conversationId,
                taskId,
                requestSummary);
    }
}
