package com.oracle.wls.agentic.workflow;

public enum WorkflowStatus {
    DRAFT,
    PROPOSED,
    AWAITING_APPROVAL,
    APPROVED,
    REJECTED,
    CANCELLED,
    QUEUED,
    IN_EXECUTION,
    COMPLETED,
    FAILED,
    APPROVAL_TIMED_OUT,
    EXECUTION_TIMED_OUT
}
