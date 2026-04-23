package com.example.wls.agentic.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowRecordTest {

    @Test
    void toSummaryContainsSharedContractFields() {
        Instant now = Instant.now();
        WorkflowRecord record = new WorkflowRecord(
                "wf-123",
                "payments-prod",
                WorkflowStatus.AWAITING_APPROVAL,
                now,
                now,
                "conv-1",
                "task-1",
                "Patch WebLogic PSU Jan 2026",
                null,
                null,
                null,
                null,
                List.of());

        WorkflowSummary summary = record.toSummary();

        assertEquals("wf-123", summary.workflowId());
        assertEquals("payments-prod", summary.domain());
        assertEquals(WorkflowStatus.AWAITING_APPROVAL, summary.currentState());
        assertEquals(now, summary.createdAt());
        assertEquals(now, summary.updatedAt());
        assertEquals("conv-1", summary.conversationId());
        assertEquals("task-1", summary.taskId());
        assertEquals("Patch WebLogic PSU Jan 2026", summary.requestSummary());
    }
}
