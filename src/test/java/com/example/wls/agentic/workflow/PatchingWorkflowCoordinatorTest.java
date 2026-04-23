package com.example.wls.agentic.workflow;

import com.example.wls.agentic.ai.WorkflowSupervisorAgent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchingWorkflowCoordinatorTest {

    @Test
    void createProposalPersistsDraftProposedAndAwaitingApproval() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        PatchingWorkflowProposalResult result = coordinator.createProposal(
                "payments-prod",
                "conv-1",
                "task-1",
                "Apply recommended PSU");

        assertTrue(result.created());
        assertNotNull(result.workflowId());
        assertNotNull(result.workflow());
        assertEquals(WorkflowStatus.AWAITING_APPROVAL, result.workflow().currentState());
        assertEquals("payments-prod", result.workflow().domain());
        assertEquals("conv-1", result.workflow().conversationId());
        assertEquals("task-1", result.workflow().taskId());

        WorkflowRecord persisted = coordinator.getByWorkflowId(result.workflowId()).orElseThrow();
        assertEquals(WorkflowStatus.AWAITING_APPROVAL, persisted.currentState());
    }

    @Test
    void conflictingSameDomainRequestReturnsExistingWorkflowId() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        PatchingWorkflowProposalResult first = coordinator.createProposal(
                "payments-prod",
                "conv-1",
                "task-1",
                "first request");
        PatchingWorkflowProposalResult second = coordinator.createProposal(
                "payments-prod",
                "conv-2",
                "task-2",
                "second request");

        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(first.workflowId(), second.conflictWorkflowId());
        assertEquals(first.workflowId(), second.workflow().workflowId());
        assertEquals(WorkflowStatus.AWAITING_APPROVAL, second.workflow().currentState());
    }

    @Test
    void queryMethodsAndListViewsReturnExpectedRecords() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        PatchingWorkflowProposalResult created = coordinator.createProposal(
                "payments-prod",
                "conv-1",
                "task-1",
                "request");

        assertEquals(created.workflowId(), coordinator.getLatestByDomain("payments-prod").orElseThrow().workflowId());
        assertEquals(created.workflowId(), coordinator.getActiveByDomain("payments-prod").orElseThrow().workflowId());

        List<WorkflowSummary> all = coordinator.listAll();
        assertEquals(1, all.size());
        assertEquals(created.workflowId(), all.get(0).workflowId());

        List<WorkflowSummary> pending = coordinator.listPendingApproval();
        assertEquals(1, pending.size());
        assertEquals(created.workflowId(), pending.get(0).workflowId());

        assertTrue(coordinator.listInExecution().isEmpty());

        WorkflowRecord approved = new WorkflowRecord(
                created.workflow().workflowId(),
                created.workflow().domain(),
                WorkflowStatus.APPROVED,
                created.workflow().createdAt(),
                created.workflow().updatedAt().plusSeconds(10),
                created.workflow().conversationId(),
                created.workflow().taskId(),
                created.workflow().requestSummary(),
                created.workflow().approvalDecision(),
                created.workflow().approvalDecisionAt(),
                created.workflow().approvalChannel(),
                created.workflow().failureReason(),
                created.workflow().steps());
        store.update(approved);

        List<WorkflowSummary> inExecution = coordinator.listInExecution();
        assertEquals(1, inExecution.size());
        assertEquals(created.workflowId(), inExecution.get(0).workflowId());
    }

    @Test
    void applyApprovalDecisionUpdatesStateAndMetadata() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        PatchingWorkflowProposalResult created = coordinator.createProposal(
                "payments-prod",
                "conv-1",
                "task-1",
                "request");

        WorkflowRecord approved = coordinator.applyApprovalDecision(
                        created.workflowId(),
                        ApprovalDecision.APPROVE,
                        WorkflowChannel.API)
                .orElseThrow();

        assertEquals(WorkflowStatus.APPROVED, approved.currentState());
        assertEquals(ApprovalDecision.APPROVE, approved.approvalDecision());
        assertEquals(WorkflowChannel.API, approved.approvalChannel());
        assertNotNull(approved.approvalDecisionAt());
    }

    @Test
    void submitApprovedWorkflowForExecutionTransitionsToCompletedAndReleasesLock() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        InMemoryDomainLockManager lockManager = new InMemoryDomainLockManager();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(
                    store,
                    lockManager,
                    new WorkflowSupervisorAgent(),
                    executor);

            PatchingWorkflowProposalResult created = coordinator.createProposal(
                    "payments-prod",
                    "conv-1",
                    "task-1",
                    "request");

            coordinator.applyApprovalDecision(created.workflowId(), ApprovalDecision.APPROVE, WorkflowChannel.API);

            WorkflowRecord queued = coordinator.submitApprovedWorkflowForExecution(created.workflowId()).orElseThrow();
            assertEquals(WorkflowStatus.QUEUED, queued.currentState());

            WorkflowRecord terminal = waitForTerminalState(coordinator, created.workflowId());
            assertEquals(WorkflowStatus.COMPLETED, terminal.currentState());
            assertFalse(lockManager.isLocked("payments-prod"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void submitApprovedWorkflowForExecutionFailureMarksFailedAndReleasesLock() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        InMemoryDomainLockManager lockManager = new InMemoryDomainLockManager();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(
                    store,
                    lockManager,
                    new WorkflowSupervisorAgent(),
                    executor);

            PatchingWorkflowProposalResult created = coordinator.createProposal(
                    "payments-prod",
                    "conv-1",
                    "task-1",
                    "FORCE_FAIL");

            coordinator.applyApprovalDecision(created.workflowId(), ApprovalDecision.APPROVE, WorkflowChannel.API);
            coordinator.submitApprovedWorkflowForExecution(created.workflowId());

            WorkflowRecord terminal = waitForTerminalState(coordinator, created.workflowId());
            assertEquals(WorkflowStatus.FAILED, terminal.currentState());
            assertFalse(lockManager.isLocked("payments-prod"));
        } finally {
            executor.shutdownNow();
        }
    }

    private static WorkflowRecord waitForTerminalState(PatchingWorkflowCoordinator coordinator, String workflowId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        WorkflowRecord latest = coordinator.getByWorkflowId(workflowId).orElseThrow();
        while (System.nanoTime() < deadline) {
            latest = coordinator.getByWorkflowId(workflowId).orElseThrow();
            if (latest.currentState() == WorkflowStatus.COMPLETED || latest.currentState() == WorkflowStatus.FAILED) {
                return latest;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return latest;
    }
}
