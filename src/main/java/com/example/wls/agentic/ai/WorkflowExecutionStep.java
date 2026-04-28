package com.example.wls.agentic.ai;

import java.util.Objects;

public record WorkflowExecutionStep(int stepNumber,
                                    String stepName,
                                    String stepQuestionTemplate,
                                    WorkflowStepAgentType agentType,
                                    String asyncOriginStepToCompleteOnSuccess) {

    public WorkflowExecutionStep {
        if (stepNumber <= 0) {
            throw new IllegalArgumentException("stepNumber must be positive");
        }
        stepName = Objects.requireNonNull(stepName, "stepName must not be null").trim();
        if (stepName.isBlank()) {
            throw new IllegalArgumentException("stepName must not be blank");
        }
        stepQuestionTemplate = Objects.requireNonNull(stepQuestionTemplate, "stepQuestionTemplate must not be null");
        agentType = Objects.requireNonNull(agentType, "agentType must not be null");
        asyncOriginStepToCompleteOnSuccess = normalizeOptional(asyncOriginStepToCompleteOnSuccess);
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
