package com.example.wls.agentic.workflow;

import java.util.Objects;

public record PatchingWorkflowProposalResult(
        boolean created,
        String workflowId,
        WorkflowRecord workflow,
        String conflictWorkflowId,
        String proposalDomain) {

    public static PatchingWorkflowProposalResult created(WorkflowRecord workflow) {
        Objects.requireNonNull(workflow, "workflow must not be null");
        return new PatchingWorkflowProposalResult(true, workflow.workflowId(), workflow, null, workflow.domain());
    }

    public static PatchingWorkflowProposalResult createdPending(String domain) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain must not be blank");
        }
        return new PatchingWorkflowProposalResult(true, null, null, null, domain);
    }

    public static PatchingWorkflowProposalResult conflict(String existingWorkflowId, WorkflowRecord existingWorkflow) {
        if (existingWorkflowId == null || existingWorkflowId.isBlank()) {
            throw new IllegalArgumentException("existingWorkflowId must not be blank");
        }
        return new PatchingWorkflowProposalResult(false, null, existingWorkflow, existingWorkflowId,
                existingWorkflow == null ? null : existingWorkflow.domain());
    }

    public static PatchingWorkflowProposalResult conflictPending(String domain) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain must not be blank");
        }
        return new PatchingWorkflowProposalResult(false, null, null, null, domain);
    }
}
