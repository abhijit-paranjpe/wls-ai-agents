package com.example.wls.agentic.dto;

import com.example.wls.agentic.workflow.ApprovalDecision;
import com.example.wls.agentic.workflow.WorkflowStatus;
import io.helidon.json.binding.Json;

@Json.Entity
public record ApprovalResponse(
        String workflowId,
        String domain,
        ApprovalDecision decision,
        WorkflowStatus currentState,
        String guidance) {
}
