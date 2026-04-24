package com.example.wls.agentic.dto;

/**
 * Structured workflow operation response returned by execution-oriented agents.
 */
public record WorkflowOperationResponse(
        String status,
        String operation,
        String domain,
        Boolean async,
        String host,
        String pid,
        String message
) {
}
