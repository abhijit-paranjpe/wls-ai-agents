package com.oracle.wls.agentic.dto;

import io.helidon.json.binding.Json;

@Json.Entity
public record AgentResponse(String message,
                            String summary,
                            TaskContext taskContext,
                            ResponseMetadata metadata) {
}
