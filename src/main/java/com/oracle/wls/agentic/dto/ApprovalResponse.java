package com.oracle.wls.agentic.dto;

import com.oracle.wls.agentic.workflow.ApprovalDecision;
import com.oracle.wls.agentic.workflow.WorkflowStatus;
import io.helidon.json.binding.Json;

@Json.Entity
public record ApprovalResponse(
        String workflowId,
        String domain,
        ApprovalDecision decision,
        WorkflowStatus currentState,
        String guidance) {
}
