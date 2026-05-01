package com.oracle.wls.agentic.dto;

import com.oracle.wls.agentic.workflow.ApprovalDecision;
import io.helidon.json.binding.Json;

@Json.Entity
public record ApprovalRequest(ApprovalDecision decision) {
}
