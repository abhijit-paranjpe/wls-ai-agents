package com.oracle.wls.agentic.rest;

import com.oracle.wls.agentic.ai.DomainRuntimeAgent;
import com.oracle.wls.agentic.ai.PatchingAgent;
import com.oracle.wls.agentic.ai.WebLogicAgent;
import com.oracle.wls.agentic.dto.AgentResponse;
import com.oracle.wls.agentic.memory.ConversationMemoryService;
import com.oracle.wls.agentic.memory.ConversationMemoryStore;
import com.oracle.wls.agentic.memory.ManagedDomainCacheService;
import com.oracle.wls.agentic.workflow.InMemoryWorkflowStateStore;
import com.oracle.wls.agentic.workflow.WorkflowStepStatus;
import com.oracle.wls.agentic.workflow.PatchingWorkflowCoordinator;
import com.oracle.wls.agentic.workflow.WorkflowRecord;
import com.oracle.wls.agentic.workflow.WorkflowApprovalSemaphore;
import com.oracle.wls.agentic.workflow.WorkflowStepRecord;
import com.oracle.wls.agentic.workflow.WorkflowStatus;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatBotEndpointWorkflowChatTest {

    @Test
    void approveWithoutWorkflowIdOrDomainAsksForExplicitReferenceWhenAmbiguous() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);
        coordinator.createProposal("payments-prod", "conv-1", "task-1", "request-1");
        coordinator.createProposal("orders-prod", "conv-1", "task-1", "request-2");

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("payments-prod", "orders-prod"));
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                mock(DomainRuntimeAgent.class),
                mock(PatchingAgent.class));

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "approve",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1"
                  }
                }
                """);

        assertTrue(response.message().contains("Please specify a workflowId or domain"));
        assertEquals(0, coordinator.listPendingApproval().size());
        verifyNoInteractions(webLogicAgent);
    }

    @Test
    void patchProposalFromChatCreatesWorkflowAndUpdatesTaskContextReferences() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        PatchingAgent patchingAgent = mock(PatchingAgent.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("payments-prod"));
        when(patchingAgent.analyzeRequest(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("""
                        {
                          "domainName": "payments-prod",
                          "applicablePatches": [
                            {"patch_id": "39043853", "reason": "OWSM Bundle Patch 14.1.2.0.260304 for security fixes"},
                            {"patch_id": "39164253", "reason": "WLS Patch Set Update 14.1.2.0.260403"}
                          ]
                        }
                        """);
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                mock(DomainRuntimeAgent.class),
                patchingAgent);

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "Apply recommended patches",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1",
                    "targetDomain": "payments-prod"
                  }
                }
                """);

        assertTrue(response.message().contains("Created patching workflow proposal"));
        assertTrue(response.message().contains("Following patches will be applied to the domain"));
        assertTrue(response.message().contains("- Patch 39043853: OWSM Bundle Patch 14.1.2.0.260304 for security fixes"));
        assertTrue(response.message().contains("- Patch 39164253: WLS Patch Set Update 14.1.2.0.260403"));
        assertTrue(response.message().contains("Use workflow actions to approve, reject, or cancel this proposal."));
        assertNotNull(response.metadata());
        assertNotNull(response.metadata().actions());
        assertEquals(3, response.metadata().actions().size());
        assertTrue(response.metadata().actions().stream().anyMatch(a ->
                "Approve".equals(a.prompt())));
        assertTrue(response.metadata().actions().stream().anyMatch(a ->
                "Reject".equals(a.prompt())));
        assertTrue(response.metadata().actions().stream().anyMatch(a ->
                "Cancel".equals(a.prompt())));
        assertFalse(response.message().contains("Choose one action:"));
        assertFalse(response.message().contains("Available actions:"));
        assertFalse(response.message().contains("Approve workflow " + response.taskContext().lastReferencedWorkflowId()));
        assertFalse(response.message().contains("Reject workflow " + response.taskContext().lastReferencedWorkflowId()));
        assertFalse(response.message().contains("Cancel workflow " + response.taskContext().lastReferencedWorkflowId()));
        assertNotNull(response.taskContext());
        assertEquals("payments-prod", response.taskContext().targetDomain());
        assertEquals(0, coordinator.listPendingApproval().size());
        verifyNoInteractions(webLogicAgent);
    }

    @Test
    void rollbackProposalFromChatCreatesWorkflowAndUpdatesTaskContextReferences() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        PatchingAgent patchingAgent = mock(PatchingAgent.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("payments-prod"));
        when(patchingAgent.analyzeRequest(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("""
                        {
                          "domainName": "payments-prod",
                          "applicablePatches": [
                            {"patch_id": "39043853", "reason": "Rollback candidate patch"}
                          ]
                        }
                        """);
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                mock(DomainRuntimeAgent.class),
                patchingAgent);

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "Rollback latest patches from domain payments-prod",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1",
                    "targetDomain": "payments-prod"
                  }
                }
                """);

        assertTrue(response.message().contains("Created rollback workflow proposal"));
        assertTrue(response.message().contains("Following latest patches will be rolled back from the domain"));
        assertTrue(response.message().contains("- Patch 39043853: Rollback candidate patch"));
        assertTrue(response.message().contains("Rollback workflow steps:"));
        assertTrue(response.message().contains("- Stop all relevant servers"));
        assertTrue(response.message().contains("- Roll back latest patches"));
        assertTrue(response.message().contains("- Start all required servers"));
        assertTrue(response.message().contains("- Verify removed patches"));
        assertTrue(response.message().contains("Use workflow actions to approve, reject, or cancel this proposal."));
        assertNotNull(response.taskContext());
        assertEquals("payments-prod", response.taskContext().targetDomain());
        verifyNoInteractions(webLogicAgent);
    }

    @Test
    void rollbackProposalWithoutPatchListDoesNotShowMisleadingPatchListTextAndShowsSteps() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        PatchingAgent patchingAgent = mock(PatchingAgent.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("payments-prod"));
        when(patchingAgent.analyzeRequest(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("No recommended patches reported.");
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                mock(DomainRuntimeAgent.class),
                patchingAgent);

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "Rollback latest patches from domain payments-prod",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1",
                    "targetDomain": "payments-prod"
                  }
                }
                """);

        assertTrue(response.message().contains("Created rollback workflow proposal"));
        assertFalse(response.message().contains("Following latest patches will be rolled back from the domain"));
        assertTrue(response.message().contains("Rollback workflow steps:"));
        assertTrue(response.message().contains("- Stop all relevant servers"));
        assertTrue(response.message().contains("- Roll back latest patches"));
        assertTrue(response.message().contains("- Start all required servers"));
        assertTrue(response.message().contains("- Verify removed patches"));
        assertTrue(response.message().contains("Specific patches to roll back will be determined during workflow execution."));
        assertTrue(response.message().contains("Use workflow actions to approve, reject, or cancel this proposal."));
        assertNotNull(response.taskContext());
        assertEquals("payments-prod", response.taskContext().targetDomain());
        verifyNoInteractions(webLogicAgent);
    }

    @Test
    void approveRollbackProposalByDomainPreservesRollbackSummaryForExecutionPlanSelection() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        PatchingAgent patchingAgent = mock(PatchingAgent.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("payments-prod"));
        when(patchingAgent.analyzeRequest(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("""
                        {
                          "domainName": "payments-prod",
                          "applicablePatches": [
                            {"patch_id": "39043853", "reason": "Rollback candidate patch"}
                          ]
                        }
                        """);
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                mock(DomainRuntimeAgent.class),
                patchingAgent);

        endpoint.chatWithAssistant("""
                {
                  "message": "Rollback latest patches from domain payments-prod",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1",
                    "targetDomain": "payments-prod"
                  }
                }
                """);

        endpoint.chatWithAssistant("""
                {
                  "message": "approve for domain payments-prod",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1",
                    "targetDomain": "payments-prod",
                    "lastUserRequest": "Rollback latest patches from domain payments-prod"
                  }
                }
                """);

        WorkflowRecord latest = coordinator.getLatestByDomain("payments-prod").orElseThrow();
        assertTrue(latest.requestSummary().toLowerCase().contains("rollback"));
    }

    @Test
    void statusForFailedWorkflowInfersCompletedAndFailedStepsFromFailureReason() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        Instant now = Instant.now();
        store.create(new WorkflowRecord(
                "1d31f83a-97a4-45d9-b96a-c06751eed596",
                "wlsoci12",
                WorkflowStatus.FAILED,
                now,
                now,
                "conv-1",
                "task-1",
                "request",
                null,
                null,
                null,
                "Workflow step did not provide async tracking identifiers: apply patches",
                List.of()));
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("wlsoci12"));
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                mock(DomainRuntimeAgent.class),
                mock(PatchingAgent.class));

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "status for workflow 1d31f83a-97a4-45d9-b96a-c06751eed596",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1"
                  }
                }
                """);

        assertTrue(response.message().contains("- ✅ Stop servers"));
        assertTrue(response.message().contains("- ❌ Apply patches"));
        assertTrue(response.message().contains("- ⏺️ Start servers"));
        assertTrue(response.message().contains("- ⏺️ Verify patch level"));
    }

    @Test
    void statusForInExecutionWorkflowDoesNotDuplicateStepInProgressLine() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        Instant now = Instant.now();
        store.create(new WorkflowRecord(
                "2d31f83a-97a4-45d9-b96a-c06751eed597",
                "wlsoci12",
                WorkflowStatus.IN_EXECUTION,
                now,
                now,
                "conv-1",
                "task-1",
                "request",
                null,
                null,
                null,
                null,
                List.of()));
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("wlsoci12"));
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                mock(DomainRuntimeAgent.class),
                mock(PatchingAgent.class));

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "status for workflow 2d31f83a-97a4-45d9-b96a-c06751eed597",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1"
                  }
                }
                """);

        int firstIndex = response.message().indexOf("- ⏳ Stop servers");
        int lastIndex = response.message().lastIndexOf("- ⏳ Stop servers");
        assertTrue(firstIndex >= 0);
        assertEquals(firstIndex, lastIndex);
        assertTrue(response.message().contains("- ⏺️ Apply patches"));
        assertTrue(response.message().contains("- ⏺️ Start servers"));
        assertTrue(response.message().contains("- ⏺️ Verify patch level"));
    }

    @Test
    void statusForInExecutionWorkflowShowsCompletedStepFromRecordedStepHistory() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        Instant now = Instant.now();
        store.create(new WorkflowRecord(
                "a344f85a-fa28-4e1c-828e-6854a1ef239b",
                "wlsoci12",
                WorkflowStatus.IN_EXECUTION,
                now,
                now,
                "conv-1",
                "task-1",
                "request",
                null,
                null,
                null,
                null,
                List.of(
                        new WorkflowStepRecord("stop servers", "stop servers", WorkflowStepStatus.COMPLETED, now.minusSeconds(120), now.minusSeconds(60), "done"),
                        new WorkflowStepRecord("apply patches", "apply patches", WorkflowStepStatus.IN_EXECUTION, now.minusSeconds(10), null, "running"))));
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("wlsoci12"));
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                mock(DomainRuntimeAgent.class),
                mock(PatchingAgent.class));

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "status for workflow a344f85a-fa28-4e1c-828e-6854a1ef239b",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1"
                  }
                }
                """);

        assertTrue(response.message().contains("- ✅ Stop servers"));
        assertTrue(response.message().contains("- ⏳ Apply patches"));
        assertTrue(response.message().contains("- ⏺️ Start servers"));
        assertTrue(response.message().contains("- ⏺️ Verify patch level"));
    }

    @Test
    void statusForRollbackWorkflowShowsRollbackSpecificStepLabels() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        Instant now = Instant.now();
        store.create(new WorkflowRecord(
                "b344f85a-fa28-4e1c-828e-6854a1ef239c",
                "wlsoci12",
                WorkflowStatus.IN_EXECUTION,
                now,
                now,
                "conv-1",
                "task-1",
                "Rollback latest patches from domain wlsoci12",
                null,
                null,
                null,
                null,
                List.of(
                        new WorkflowStepRecord("initiate-stop-servers", "initiate-stop-servers", WorkflowStepStatus.COMPLETED, now.minusSeconds(120), now.minusSeconds(60), "done"),
                        new WorkflowStepRecord("rollback-latest-patches", "rollback-latest-patches", WorkflowStepStatus.IN_EXECUTION, now.minusSeconds(10), null, "running"))));
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("wlsoci12"));
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                mock(DomainRuntimeAgent.class),
                mock(PatchingAgent.class));

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "status for workflow b344f85a-fa28-4e1c-828e-6854a1ef239c",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1"
                  }
                }
                """);

        assertTrue(response.message().contains("- ✅ Stop servers"));
        assertTrue(response.message().contains("- ⏳ Roll back latest patches"));
        assertTrue(response.message().contains("- ⏺️ Start servers"));
        assertTrue(response.message().contains("- ⏺️ Verify removed patches"));
        assertFalse(response.message().contains("- ⏳ Apply patches"));
    }

    @Test
    void statusForApplyWorkflowStillShowsApplyAndVerifyPatchLevelLabels() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        Instant now = Instant.now();
        store.create(new WorkflowRecord(
                "c344f85a-fa28-4e1c-828e-6854a1ef239d",
                "wlsoci12",
                WorkflowStatus.IN_EXECUTION,
                now,
                now,
                "conv-1",
                "task-1",
                "Apply recommended patches to domain wlsoci12",
                null,
                null,
                null,
                null,
                List.of(
                        new WorkflowStepRecord("initiate-stop-servers", "initiate-stop-servers", WorkflowStepStatus.COMPLETED, now.minusSeconds(120), now.minusSeconds(60), "done"),
                        new WorkflowStepRecord("apply-latest-patches", "apply-latest-patches", WorkflowStepStatus.IN_EXECUTION, now.minusSeconds(10), null, "running"))));
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("wlsoci12"));
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                mock(DomainRuntimeAgent.class),
                mock(PatchingAgent.class));

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "status for workflow c344f85a-fa28-4e1c-828e-6854a1ef239d",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1"
                  }
                }
                """);

        assertTrue(response.message().contains("- ✅ Stop servers"));
        assertTrue(response.message().contains("- ⏳ Apply patches"));
        assertTrue(response.message().contains("- ⏺️ Start servers"));
        assertTrue(response.message().contains("- ⏺️ Verify patch level"));
        assertFalse(response.message().contains("Roll back latest patches"));
        assertFalse(response.message().contains("Verify removed patches"));
    }

    @Test
    void statusForRollbackWorkflowUsesRollbackLabelsWhenSignalIsInStepDetails() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        Instant now = Instant.now();
        store.create(new WorkflowRecord(
                "d344f85a-fa28-4e1c-828e-6854a1ef239e",
                "wlsoci12",
                WorkflowStatus.IN_EXECUTION,
                now,
                now,
                "conv-1",
                "task-1",
                "Execute approved patching workflow for domain wlsoci12",
                null,
                null,
                null,
                null,
                List.of(
                        new WorkflowStepRecord("initiate-stop-servers", "initiate-stop-servers", WorkflowStepStatus.COMPLETED, now.minusSeconds(120), now.minusSeconds(60), "done"),
                        new WorkflowStepRecord("monitor-patch-completion", "monitor-patch-completion", WorkflowStepStatus.IN_EXECUTION, now.minusSeconds(10), null,
                                "{\"status\":\"running\",\"operation\":\"rollback-latest-patches\"}"))));
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("wlsoci12"));
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                mock(DomainRuntimeAgent.class),
                mock(PatchingAgent.class));

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "status for workflow d344f85a-fa28-4e1c-828e-6854a1ef239e",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1"
                  }
                }
                """);

        assertTrue(response.message().contains("- ✅ Stop servers"));
        assertTrue(response.message().contains("- ⏳ Roll back latest patches"));
        assertTrue(response.message().contains("- ⏺️ Start servers"));
        assertTrue(response.message().contains("- ⏺️ Verify removed patches"));
        assertFalse(response.message().contains("- ⏳ Apply patches"));
    }

    @Test
    void nonWorkflowAsyncTrackingResponseRemainsUserFacingText() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        DomainRuntimeAgent runtimeAgent = mock(DomainRuntimeAgent.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("payments-prod"));
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 514591 on host wlsoci12-wls-0")))
                .thenReturn("Async job is still running on host wlsoci12-wls-0 with PID 514591.");
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                runtimeAgent,
                mock(PatchingAgent.class));

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "Track async job status for PID 514591 on host wlsoci12-wls-0",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1"
                  }
                }
                """);

        assertTrue(response.message().contains("Async job is still running"));
        assertNotNull(response.metadata());
        assertTrue(response.metadata().actions().stream().anyMatch(a ->
                "Track async job status for PID 514591 on host wlsoci12-wls-0".equals(a.prompt())));
        verifyNoInteractions(webLogicAgent);
    }

    @Test
    void nonWorkflowAsyncTrackingAcceptsUnlabeledForOnPhrase() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        DomainRuntimeAgent runtimeAgent = mock(DomainRuntimeAgent.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("payments-prod"));
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 514591 on host wlsoci12-wls-0")))
                .thenReturn("Async job is still running on host wlsoci12-wls-0 with PID 514591.");
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                runtimeAgent,
                mock(PatchingAgent.class));

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "Track async job status for 514591 on wlsoci12-wls-0",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1"
                  }
                }
                """);

        assertTrue(response.message().contains("Async job is still running"));
        verifyNoInteractions(webLogicAgent);
    }

    @Test
    void nonWorkflowAsyncTrackingAcceptsTrackPidOnHostPhrase() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        DomainRuntimeAgent runtimeAgent = mock(DomainRuntimeAgent.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("payments-prod"));
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 514591 on host wlsoci12-wls-0")))
                .thenReturn("Async job is still running on host wlsoci12-wls-0 with PID 514591.");
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                runtimeAgent,
                mock(PatchingAgent.class));

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "Track 514591 on host wlsoci12-wls-0",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1"
                  }
                }
                """);

        assertTrue(response.message().contains("Async job is still running"));
        verifyNoInteractions(webLogicAgent);
    }

    @Test
    void nonWorkflowAsyncTrackingAcceptsTrackPidOnHostWithoutHostKeyword() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        DomainRuntimeAgent runtimeAgent = mock(DomainRuntimeAgent.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("payments-prod"));
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 514591 on host wlsoci12-wls-0")))
                .thenReturn("Async job is still running on host wlsoci12-wls-0 with PID 514591.");
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                runtimeAgent,
                mock(PatchingAgent.class));

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "Track 514591 on wlsoci12-wls-0",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1"
                  }
                }
                """);

        assertTrue(response.message().contains("Async job is still running"));
        verifyNoInteractions(webLogicAgent);
    }

    @Test
    void nonWorkflowAsyncTrackingCompletedJsonSuppressesFollowUpActionAndNormalizesMessage() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        DomainRuntimeAgent runtimeAgent = mock(DomainRuntimeAgent.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("wlsucm14c"));
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 1736784 on host wlsucm14c-wls-0")))
                .thenReturn("""
                        { "status": "completed", "operation": "start-servers", "domain": "wlsucm14c",
                          "hostPids": {"wlsucm14c-wls-0": "1736784"}, "message": "Async job completed successfully" }
                        """);
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                runtimeAgent,
                mock(PatchingAgent.class));

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "Track 1736784 on wlsucm14c-wls-0",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1"
                  }
                }
                """);

        assertEquals(
                "Async job status on host wlsucm14c-wls-0 for PID 1736784: completed.",
                response.message());
        assertTrue(response.metadata() == null
                || response.metadata().actions() == null
                || response.metadata().actions().isEmpty());
        verifyNoInteractions(webLogicAgent);
    }

    @Test
    void approveWorkflowResponseContainsStatusActionMetadataWithoutDuplicateStatusLine() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("payments-prod"));
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                mock(DomainRuntimeAgent.class),
                mock(PatchingAgent.class));

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "approve for domain payments-prod",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1"
                  }
                }
                """);

        assertTrue(response.message().contains("Execution has been queued."));
        assertNotNull(response.taskContext().lastReferencedWorkflowId());
        assertFalse(response.message().contains("Check status for workflow " + response.taskContext().lastReferencedWorkflowId()));
        assertNotNull(response.metadata());
        assertTrue(response.metadata().actions().stream().anyMatch(a ->
                ("Check status for workflow " + response.taskContext().lastReferencedWorkflowId()).equals(a.prompt())));
    }

    @Test
    void nonWorkflowRuntimeJsonResponseIsNormalizedWithOperationHostPidAndStatus() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("payments-prod"));
        when(webLogicAgent.chat(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AgentResponse(
                        """
                        {"status":"started","operation":"start-servers","domain":"payments-prod","async":true,
                         "host":"wlsoci12-wls-0","pid":"514591","message":"Start initiated"}
                        """,
                        "",
                        null,
                        null));
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                mock(DomainRuntimeAgent.class),
                mock(PatchingAgent.class));

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "start all servers in domain payments-prod",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1"
                  }
                }
                """);

        assertEquals(
                "Start servers is started on host wlsoci12-wls-0 (PID 514591).",
                response.message());
    }

    @Test
    void latestPatchStatusJsonResponseIsNormalizedToUserFriendlyLatestMessage() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("wlsucm14c_domain"));
        when(webLogicAgent.chat(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AgentResponse(
                        """
                        {
                          "domainName": "wlsucm14c_domain",
                          "isDomainOnLatestPatches": true,
                          "results": [
                            {
                              "hostName": "wlsucm14c-wls-0",
                              "applicablePatches": []
                            }
                          ]
                        }
                        """,
                        "",
                        null,
                        null));
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                mock(DomainRuntimeAgent.class),
                mock(PatchingAgent.class));

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "Is my domain on latest patches?",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1",
                    "targetDomain": "wlsucm14c_domain"
                  }
                }
                """);

        assertEquals("Domain 'wlsucm14c_domain' is already on the latest patches.", response.message());
    }

    @Test
    void latestPatchStatusJsonResponseIsNormalizedToApplicablePatchesMessageWhenNotLatest() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("wlsucm14c_domain"));
        when(webLogicAgent.chat(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AgentResponse(
                        """
                        {
                          "domainName": "wlsucm14c_domain",
                          "isDomainOnLatestPatches": false,
                          "results": [
                            {
                              "hostName": "wlsucm14c-wls-0",
                              "applicablePatches": [
                                {"patch_id": "39164253", "reason": "WLS PSU required"}
                              ]
                            }
                          ]
                        }
                        """,
                        "",
                        null,
                        null));
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                mock(DomainRuntimeAgent.class),
                mock(PatchingAgent.class));

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "Is my domain on latest patches?",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1",
                    "targetDomain": "wlsucm14c_domain"
                  }
                }
                """);

        assertTrue(response.message().contains("Domain 'wlsucm14c_domain' is not on the latest patches. Applicable patches:"));
        assertTrue(response.message().contains("- Patch 39164253: WLS PSU required"));
    }

    @Test
    void nonWorkflowRuntimeJsonResponseWithMissingHostPidIsFlaggedAsIncomplete() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("payments-prod"));
        when(webLogicAgent.chat(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AgentResponse(
                        """
                        {"status":"started","operation":"start-servers","domain":"payments-prod","async":true,
                         "host":"","pid":"","message":"Start initiated"}
                        """,
                        "",
                        null,
                        null));
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                mock(DomainRuntimeAgent.class),
                mock(PatchingAgent.class));

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "start all servers in domain payments-prod",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1"
                  }
                }
                """);

        assertTrue(response.message().contains("Runtime operation response is incomplete"));
        assertTrue(response.message().contains("missing: host, pid"));
    }

    @Test
    void nonWorkflowRuntimeJsonResponseExtractsHostPidFromMessageWhenTopLevelFieldsMissing() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("wlsucm14c_domain"));
        when(webLogicAgent.chat(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AgentResponse(
                        """
                        {"status":"running","operation":"start-servers","domain":"wlsucm14c_domain","async":true,
                         "host":"","pid":"","message":"Start servers operation initiated for wlsucm14c_domain on host wlsucm14c-wls-0 with PID 1100214."}
                        """,
                        "",
                        null,
                        null));
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                mock(DomainRuntimeAgent.class),
                mock(PatchingAgent.class));

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "start all servers in domain wlsucm14c_domain",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1"
                  }
                }
                """);

        assertEquals(
                "Start servers is running on host wlsucm14c-wls-0 (PID 1100214).",
                response.message());
    }

    @Test
    void nonWorkflowRuntimeJsonResponseUsesHostPidsMapWhenTopLevelHostPidMissing() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("wlsucm14c_domain"));
        when(webLogicAgent.chat(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AgentResponse(
                        """
                        {"status":"started","operation":"start-servers","domain":"wlsucm14c_domain","async":true,
                         "host":"","pid":"","hostPids":{"wlsucm14c-wls-0":"1100214"},
                         "message":"Start servers operation initiated on domain wlsucm14c_domain."}
                        """,
                        "",
                        null,
                        null));
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                mock(DomainRuntimeAgent.class),
                mock(PatchingAgent.class));

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "start all servers in domain wlsucm14c_domain",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1"
                  }
                }
                """);

        assertEquals(
                "Start servers is started on host wlsucm14c-wls-0 (PID 1100214).",
                response.message());
    }

    @Test
    void nonWorkflowRuntimeJsonDomainValidationMessageIsReturnedWithoutIncompleteWrapper() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("payments-prod"));
        when(webLogicAgent.chat(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AgentResponse(
                        """
                        {"status":"unknown","operation":"start-servers","domain":"","host":"","pid":"",
                         "message":"Domain name is required to start all servers. Please provide the domain name."}
                        """,
                        "",
                        null,
                        null));
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                mock(DomainRuntimeAgent.class),
                mock(PatchingAgent.class));

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "start all servers",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1"
                  }
                }
                """);

        assertEquals(
                "Domain name is required to start all servers. Please provide the domain name.",
                response.message());
    }

    @Test
    void diagnosticReportResponseAddsDetailedAnalysisClickableAction() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);

        WebLogicAgent webLogicAgent = mock(WebLogicAgent.class);
        ManagedDomainCacheService domainCacheService = mock(ManagedDomainCacheService.class);
        when(domainCacheService.getDomains()).thenReturn(List.of("wlsucm14c_domain"));
        when(webLogicAgent.chat(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AgentResponse(
                        "RDA report generated and available at https://example.com/reports/rda-1736784.zip",
                        "",
                        null,
                        null));
        ConversationMemoryService memoryService = mockMemoryService();

        ChatBotEndpoint endpoint = new ChatBotEndpoint(
                webLogicAgent,
                memoryService,
                domainCacheService,
                coordinator,
                new WorkflowApprovalSemaphore(),
                mock(DomainRuntimeAgent.class),
                mock(PatchingAgent.class));

        AgentResponse response = endpoint.chatWithAssistant("""
                {
                  "message": "get diagnostic report for 1736784 on wlsucm14c-wls-0",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1"
                  }
                }
                """);

        assertNotNull(response.metadata());
        assertNotNull(response.metadata().actions());
        assertTrue(response.metadata().actions().stream().anyMatch(a ->
                "Show detailed diagnostic analysis for report: https://example.com/reports/rda-1736784.zip"
                        .equals(a.prompt())));
    }

    private static ConversationMemoryService mockMemoryService() {
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        ConversationMemoryStore store = mock(ConversationMemoryStore.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        when(memoryService.store()).thenReturn(store);
        when(memoryService.chatMemory(org.mockito.ArgumentMatchers.anyString())).thenReturn(chatMemory);
        when(chatMemory.messages()).thenReturn(List.<ChatMessage>of());
        when(store.loadSummary(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        when(store.loadTaskContext(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        return memoryService;
    }
}
