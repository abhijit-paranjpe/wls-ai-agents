package com.example.wls.agentic.dto;

import io.helidon.json.binding.Json;

@Json.Entity
public record ResponseAction(String type,
                             String label,
                             String prompt,
                             String workflowId) {
}
