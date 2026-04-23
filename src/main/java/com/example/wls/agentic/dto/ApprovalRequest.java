package com.example.wls.agentic.dto;

import com.example.wls.agentic.workflow.ApprovalDecision;
import io.helidon.json.binding.Json;

@Json.Entity
public record ApprovalRequest(ApprovalDecision decision) {
}
