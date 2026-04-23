package com.example.wls.agentic.workflow;

import java.util.Objects;

public record PatchingWorkflowProposalResult(
        boolean created,
        String workflowId,
        WorkflowRecord workflow,
        String conflictWorkflowId) {

    public static PatchingWorkflowProposalResult created(WorkflowRecord workflow) {
        Objects.requireNonNull(workflow, "workflow must not be null");
        return new PatchingWorkflowProposalResult(true, workflow.workflowId(), workflow, null);
    }

    public static PatchingWorkflowProposalResult conflict(String existingWorkflowId, WorkflowRecord existingWorkflow) {
        if (existingWorkflowId == null || existingWorkflowId.isBlank()) {
            throw new IllegalArgumentException("existingWorkflowId must not be blank");
        }
        return new PatchingWorkflowProposalResult(false, null, existingWorkflow, existingWorkflowId);
    }
}
