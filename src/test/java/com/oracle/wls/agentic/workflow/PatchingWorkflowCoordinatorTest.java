package com.oracle.wls.agentic.workflow;

import com.oracle.wls.agentic.ai.ApplyLatestPatchesWorkflowService;
import com.oracle.wls.agentic.ai.RollbackLatestPatchesWorkflowService;
import com.oracle.wls.agentic.ai.WorkflowExecutionSequenceAgent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatchingWorkflowCoordinatorTest {

    private static final ApplyLatestPatchesWorkflowService APPLY_SERVICE = new ApplyLatestPatchesWorkflowService();
    private static final RollbackLatestPatchesWorkflowService ROLLBACK_SERVICE = new RollbackLatestPatchesWorkflowService();

    @Test
    void createProposalDoesNotPersistWorkflowBeforeApproval() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        PatchingWorkflowProposalResult result = coordinator.createProposal(
                "payments-prod",
                "conv-1",
                "task-1",
                "Apply recommended PSU");

        assertTrue(result.created());
        assertEquals("payments-prod", result.proposalDomain());
        assertEquals(null, result.workflowId());
        assertEquals(null, result.workflow());
        assertTrue(coordinator.listAll().isEmpty());
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
        assertTrue(first.created());

        WorkflowRecord created = coordinator.applyApprovalDecisionByDomain(
                        "payments-prod",
                        ApprovalDecision.APPROVE,
                        WorkflowChannel.API,
                        "conv-1",
                        "task-1",
                        "first request")
                .orElseThrow();

        PatchingWorkflowProposalResult second = coordinator.createProposal(
                "payments-prod",
                "conv-2",
                "task-2",
                "second request");

        assertFalse(second.created());
        assertEquals(created.workflowId(), second.conflictWorkflowId());
        assertEquals(created.workflowId(), second.workflow().workflowId());
        assertEquals(WorkflowStatus.APPROVED, second.workflow().currentState());
    }

    @Test
    void queryMethodsAndListViewsReturnExpectedRecords() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        PatchingWorkflowProposalResult proposal = coordinator.createProposal(
                "payments-prod",
                "conv-1",
                "task-1",
                "request");
        assertTrue(proposal.created());

        WorkflowRecord created = coordinator.applyApprovalDecisionByDomain(
                        "payments-prod",
                        ApprovalDecision.APPROVE,
                        WorkflowChannel.CHAT,
                        "conv-1",
                        "task-1",
                        "request")
                .orElseThrow();

        assertEquals(created.workflowId(), coordinator.getLatestByDomain("payments-prod").orElseThrow().workflowId());
        assertEquals(created.workflowId(), coordinator.getActiveByDomain("payments-prod").orElseThrow().workflowId());

        List<WorkflowSummary> all = coordinator.listAll();
        assertEquals(1, all.size());
        assertEquals(created.workflowId(), all.get(0).workflowId());

        List<WorkflowSummary> pending = coordinator.listPendingApproval();
        assertTrue(pending.isEmpty());

        assertEquals(1, coordinator.listInExecution().size());

        WorkflowRecord approved = new WorkflowRecord(
                created.workflowId(),
                created.domain(),
                WorkflowStatus.APPROVED,
                created.createdAt(),
                created.updatedAt().plusSeconds(10),
                created.conversationId(),
                created.taskId(),
                created.requestSummary(),
                created.approvalDecision(),
                created.approvalDecisionAt(),
                created.approvalChannel(),
                created.failureReason(),
                created.steps());
        store.update(approved);

        List<WorkflowSummary> inExecution = coordinator.listInExecution();
        assertEquals(1, inExecution.size());
        assertEquals(created.workflowId(), inExecution.get(0).workflowId());
    }

    @Test
    void applyApprovalDecisionUpdatesStateAndMetadata() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WorkflowRecord approved = coordinator.applyApprovalDecisionByDomain(
                        "payments-prod",
                        ApprovalDecision.APPROVE,
                        WorkflowChannel.API,
                        "conv-1",
                        "task-1",
                        "request")
                .orElseThrow();

        assertEquals(WorkflowStatus.APPROVED, approved.currentState());
        assertEquals(ApprovalDecision.APPROVE, approved.approvalDecision());
        assertEquals(WorkflowChannel.API, approved.approvalChannel());
        assertNotNull(approved.approvalDecisionAt());
    }

    @Test
    void submitApprovedWorkflowForExecutionMarksWorkflowCompletedAndReleasesLock() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        InMemoryDomainLockManager lockManager = new InMemoryDomainLockManager();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            WorkflowExecutionSequenceAgent sequenceAgent = mock(WorkflowExecutionSequenceAgent.class);
            doAnswer(invocation -> {
                String workflowId = invocation.getArgument(0, String.class);
                WorkflowRecord current = store.getByWorkflowId(workflowId).orElseThrow();
                Instant now = Instant.now();
                store.update(new WorkflowRecord(
                        current.workflowId(),
                        current.domain(),
                        current.currentState(),
                        current.createdAt(),
                        now,
                        current.conversationId(),
                        current.taskId(),
                        current.requestSummary(),
                        current.approvalDecision(),
                        current.approvalDecisionAt(),
                        current.approvalChannel(),
                        current.failureReason(),
                        List.of(
                                new WorkflowStepRecord("stop servers", "stop servers", WorkflowStepStatus.COMPLETED, now.minusSeconds(60), now.minusSeconds(50), "ok"),
                                new WorkflowStepRecord("apply patches", "apply patches", WorkflowStepStatus.COMPLETED, now.minusSeconds(49), now.minusSeconds(30), "ok"),
                                new WorkflowStepRecord("start servers", "start servers", WorkflowStepStatus.COMPLETED, now.minusSeconds(29), now.minusSeconds(15), "ok"),
                                new WorkflowStepRecord("verify patch level", "verify patch level", WorkflowStepStatus.COMPLETED, now.minusSeconds(14), now.minusSeconds(1), "ok"))));
                return "ok";
            }).when(sequenceAgent).run(anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any());

            PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(
                    store,
                    lockManager,
                    sequenceAgent,
                    APPLY_SERVICE,
                    ROLLBACK_SERVICE,
                    executor,
                    Duration.ofMillis(50));

            WorkflowRecord created = coordinator.applyApprovalDecisionByDomain(
                            "payments-prod",
                            ApprovalDecision.APPROVE,
                            WorkflowChannel.API,
                            "conv-1",
                            "task-1",
                            "request")
                    .orElseThrow();

            WorkflowRecord queued = coordinator.submitApprovedWorkflowForExecution(created.workflowId()).orElseThrow();
            assertEquals(WorkflowStatus.QUEUED, queued.currentState());

            WorkflowRecord terminal = waitForTerminalState(coordinator, created.workflowId());
            assertEquals(WorkflowStatus.COMPLETED, terminal.currentState());
            assertFalse(lockManager.isLocked("payments-prod"));
            verify(sequenceAgent).run(
                    created.workflowId(),
                    "payments-prod",
                    "Execute approved patching workflow for domain payments-prod",
                    "Can you initiate stop servers for domain payments-prod and return host and pids in response?",
                    APPLY_SERVICE.plan());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void submitApprovedWorkflowForExecutionFailsWhenRequiredStepsAreMissing() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        InMemoryDomainLockManager lockManager = new InMemoryDomainLockManager();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            WorkflowExecutionSequenceAgent sequenceAgent = mock(WorkflowExecutionSequenceAgent.class);
            doAnswer(invocation -> {
                String workflowId = invocation.getArgument(0, String.class);
                WorkflowRecord current = store.getByWorkflowId(workflowId).orElseThrow();
                Instant now = Instant.now();
                store.update(new WorkflowRecord(
                        current.workflowId(),
                        current.domain(),
                        current.currentState(),
                        current.createdAt(),
                        now,
                        current.conversationId(),
                        current.taskId(),
                        current.requestSummary(),
                        current.approvalDecision(),
                        current.approvalDecisionAt(),
                        current.approvalChannel(),
                        current.failureReason(),
                        List.of(new WorkflowStepRecord(
                                "stop servers",
                                "stop servers",
                                WorkflowStepStatus.COMPLETED,
                                now.minusSeconds(20),
                                now.minusSeconds(10),
                                "ok"))));
                return "partial";
            }).when(sequenceAgent).run(anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any());

            PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(
                    store,
                    lockManager,
                    sequenceAgent,
                    APPLY_SERVICE,
                    ROLLBACK_SERVICE,
                    executor,
                    Duration.ofMillis(50));

            WorkflowRecord created = coordinator.applyApprovalDecisionByDomain(
                            "payments-prod",
                            ApprovalDecision.APPROVE,
                            WorkflowChannel.API,
                            "conv-1",
                            "task-1",
                            "request")
                    .orElseThrow();

            coordinator.submitApprovedWorkflowForExecution(created.workflowId()).orElseThrow();

            WorkflowRecord terminal = waitForTerminalState(coordinator, created.workflowId());
            assertEquals(WorkflowStatus.FAILED, terminal.currentState());
            assertTrue(terminal.failureReason().contains("Workflow did not complete required step"));
            assertFalse(lockManager.isLocked("payments-prod"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void submitApprovedWorkflowForExecutionFailureMarksFailedAndReleasesLock() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        InMemoryDomainLockManager lockManager = new InMemoryDomainLockManager();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            WorkflowExecutionSequenceAgent sequenceAgent = mock(WorkflowExecutionSequenceAgent.class);
            when(sequenceAgent.run(
                    contains(""),
                    contains(""),
                    contains("Execute approved patching workflow"),
                    contains("initiate stop servers"),
                    org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new IllegalStateException("Controlled execution failure requested"));

            PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(
                    store,
                    lockManager,
                    sequenceAgent,
                    APPLY_SERVICE,
                    ROLLBACK_SERVICE,
                    executor,
                    Duration.ofMillis(50));

            WorkflowRecord created = coordinator.applyApprovalDecisionByDomain(
                            "payments-prod",
                            ApprovalDecision.APPROVE,
                            WorkflowChannel.API,
                            "conv-1",
                            "task-1",
                            "FORCE_FAIL")
                    .orElseThrow();
            coordinator.submitApprovedWorkflowForExecution(created.workflowId());

            WorkflowRecord terminal = waitForTerminalState(coordinator, created.workflowId());
            assertEquals(WorkflowStatus.FAILED, terminal.currentState());
            assertTrue(terminal.failureReason().contains("Controlled execution failure requested"));
            assertFalse(lockManager.isLocked("payments-prod"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void submitApprovedWorkflowForExecutionReturnedFailedStatusMarksFailedAndReleasesLock() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        InMemoryDomainLockManager lockManager = new InMemoryDomainLockManager();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            WorkflowExecutionSequenceAgent sequenceAgent = mock(WorkflowExecutionSequenceAgent.class);
            when(sequenceAgent.run(
                    contains(""),
                    contains(""),
                    contains("Execute approved patching workflow"),
                    contains("initiate stop servers"),
                    org.mockito.ArgumentMatchers.any()))
                    .thenReturn("""
                            {"status":"failed","operation":"apply-recommended-patches","domain":"payments-prod","message":"Failed to submit job as there is an existing job running."}
                            """);

            PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(
                    store,
                    lockManager,
                    sequenceAgent,
                    APPLY_SERVICE,
                    ROLLBACK_SERVICE,
                    executor,
                    Duration.ofMillis(50));

            WorkflowRecord created = coordinator.applyApprovalDecisionByDomain(
                            "payments-prod",
                            ApprovalDecision.APPROVE,
                            WorkflowChannel.API,
                            "conv-1",
                            "task-1",
                            "request")
                    .orElseThrow();

            coordinator.submitApprovedWorkflowForExecution(created.workflowId()).orElseThrow();

            WorkflowRecord terminal = waitForTerminalState(coordinator, created.workflowId());
            assertEquals(WorkflowStatus.FAILED, terminal.currentState());
            assertTrue(terminal.failureReason().contains("existing job running"));
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
            if (latest.currentState() == WorkflowStatus.FAILED || latest.currentState() == WorkflowStatus.COMPLETED) {
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
