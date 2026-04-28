package com.example.wls.agentic.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryWorkflowStateStoreTest {

    @Test
    void supportsLifecycleQueryAndListingOperations() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();

        Instant t1 = Instant.parse("2026-04-23T10:00:00Z");
        Instant t2 = Instant.parse("2026-04-23T10:05:00Z");
        Instant t3 = Instant.parse("2026-04-23T10:06:00Z");
        Instant t4 = Instant.parse("2026-04-23T10:07:00Z");

        WorkflowRecord wf1Draft = record("wf-1", "payments-prod", WorkflowStatus.DRAFT, t1, t1);
        store.create(wf1Draft);

        WorkflowRecord wf1Proposed = record("wf-1", "payments-prod", WorkflowStatus.PROPOSED, t1, t2);
        store.update(wf1Proposed);

        WorkflowRecord wf1Awaiting = record("wf-1", "payments-prod", WorkflowStatus.AWAITING_APPROVAL, t1, t3);
        store.update(wf1Awaiting);

        WorkflowRecord wf2Queued = record("wf-2", "orders-prod", WorkflowStatus.QUEUED, t2, t4);
        store.create(wf2Queued);

        assertEquals(WorkflowStatus.AWAITING_APPROVAL,
                store.getByWorkflowId("wf-1").orElseThrow().currentState());
        assertEquals("wf-1", store.getLatestByDomain("payments-prod").orElseThrow().workflowId());
        assertEquals("wf-1", store.getActiveByDomain("payments-prod").orElseThrow().workflowId());

        List<WorkflowRecord> all = store.listAll();
        assertEquals(2, all.size());
        assertEquals("wf-2", all.get(0).workflowId());

        List<WorkflowRecord> pendingApproval = store.listByStatus(WorkflowStatus.AWAITING_APPROVAL);
        assertEquals(1, pendingApproval.size());
        assertEquals("wf-1", pendingApproval.get(0).workflowId());

        List<WorkflowRecord> inExecution = store.listByStatus(WorkflowStatus.QUEUED);
        assertEquals(1, inExecution.size());
        assertEquals("wf-2", inExecution.get(0).workflowId());
    }

    @Test
    void activeByDomainIgnoresTerminalWorkflows() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();

        Instant t1 = Instant.parse("2026-04-23T11:00:00Z");
        Instant t2 = Instant.parse("2026-04-23T11:10:00Z");

        store.create(record("wf-1", "inventory-prod", WorkflowStatus.AWAITING_APPROVAL, t1, t1));
        store.create(record("wf-2", "inventory-prod", WorkflowStatus.COMPLETED, t2, t2));

        assertEquals("wf-2", store.getLatestByDomain("inventory-prod").orElseThrow().workflowId());
        assertEquals("wf-1", store.getActiveByDomain("inventory-prod").orElseThrow().workflowId());
    }

    @Test
    void domainQueriesAreCaseInsensitive() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        Instant now = Instant.parse("2026-04-23T12:00:00Z");
        store.create(record("wf-1", "Payments-Prod", WorkflowStatus.AWAITING_APPROVAL, now, now));

        assertTrue(store.getLatestByDomain("payments-prod").isPresent());
        assertTrue(store.getActiveByDomain("PAYMENTS-PROD").isPresent());
    }

    private static WorkflowRecord record(String workflowId,
                                         String domain,
                                         WorkflowStatus status,
                                         Instant createdAt,
                                         Instant updatedAt) {
        return new WorkflowRecord(
                workflowId,
                domain,
                status,
                createdAt,
                updatedAt,
                "conv-1",
                "task-1",
                "request",
                null,
                null,
                null,
                null,
                List.of());
    }
}