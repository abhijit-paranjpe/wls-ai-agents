package com.example.wls.agentic.workflow;

import io.helidon.json.binding.Json;

import java.time.Instant;

@Json.Entity
public record WorkflowStepRecord(
        String stepId,
        String name,
        WorkflowStatus state,
        Instant startedAt,
        Instant endedAt,
        String details) {
}
