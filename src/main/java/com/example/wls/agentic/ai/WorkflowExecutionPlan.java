package com.example.wls.agentic.ai;

import java.util.List;
import java.util.Objects;

public record WorkflowExecutionPlan(String workflowName, List<WorkflowExecutionStep> steps) {

    public WorkflowExecutionPlan {
        workflowName = workflowName == null ? "" : workflowName;
        steps = List.copyOf(Objects.requireNonNull(steps, "steps must not be null"));
    }
}
