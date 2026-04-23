package com.example.wls.agentic.dto;

import com.example.wls.agentic.workflow.WorkflowSummary;
import io.helidon.json.binding.Json;

import java.util.List;

@Json.Entity
public record WorkflowSummaryListResponse(List<WorkflowSummary> workflows) {
}
