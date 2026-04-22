package com.example.wls.agentic.workflow;

public record PatchExecutionResult(
        String stopResult,
        String applyResult,
        String startResult,
        String verifyResult) {
}