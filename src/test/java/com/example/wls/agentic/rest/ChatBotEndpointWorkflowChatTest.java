package com.example.wls.agentic.rest;

import com.example.wls.agentic.ai.DomainRuntimeAgent;
import com.example.wls.agentic.ai.PatchingAgent;
import com.example.wls.agentic.ai.WebLogicAgent;
import com.example.wls.agentic.dto.AgentResponse;
import com.example.wls.agentic.memory.ConversationMemoryService;
import com.example.wls.agentic.memory.ConversationMemoryStore;
import com.example.wls.agentic.memory.ManagedDomainCacheService;
import com.example.wls.agentic.workflow.InMemoryWorkflowStateStore;
import com.example.wls.agentic.workflow.PatchingWorkflowCoordinator;
import com.example.wls.agentic.workflow.WorkflowRecord;
import com.example.wls.agentic.workflow.WorkflowApprovalSemaphore;
import com.example.wls.agentic.workflow.WorkflowStepRecord;
import com.example.wls.agentic.workflow.WorkflowStatus;
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
        assertEquals(2, coordinator.listPendingApproval().size());
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
                "Approve workflow ".concat(response.taskContext().lastReferencedWorkflowId()).equals(a.prompt())));
        assertTrue(response.metadata().actions().stream().anyMatch(a ->
                "Reject workflow ".concat(response.taskContext().lastReferencedWorkflowId()).equals(a.prompt())));
        assertTrue(response.metadata().actions().stream().anyMatch(a ->
                "Cancel workflow ".concat(response.taskContext().lastReferencedWorkflowId()).equals(a.prompt())));
        assertFalse(response.message().contains("Choose one action:"));
        assertFalse(response.message().contains("Available actions:"));
        assertFalse(response.message().contains("Approve workflow " + response.taskContext().lastReferencedWorkflowId()));
        assertFalse(response.message().contains("Reject workflow " + response.taskContext().lastReferencedWorkflowId()));
        assertFalse(response.message().contains("Cancel workflow " + response.taskContext().lastReferencedWorkflowId()));
        assertNotNull(response.taskContext());
        assertNotNull(response.taskContext().lastReferencedWorkflowId());
        assertFalse(response.taskContext().activeWorkflowIds().isEmpty());
        assertEquals(response.taskContext().lastReferencedWorkflowId(), response.taskContext().activeWorkflowIds().getFirst());

        assertEquals(
                WorkflowStatus.AWAITING_APPROVAL,
                coordinator.getByWorkflowId(response.taskContext().lastReferencedWorkflowId()).orElseThrow().currentState());
        verifyNoInteractions(webLogicAgent);
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
                        new WorkflowStepRecord("stop servers", "stop servers", WorkflowStatus.COMPLETED, now.minusSeconds(120), now.minusSeconds(60), "done"),
                        new WorkflowStepRecord("apply patches", "apply patches", WorkflowStatus.IN_EXECUTION, now.minusSeconds(10), null, "running"))));
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
    void approveWorkflowResponseContainsStatusActionMetadataWithoutDuplicateStatusLine() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        PatchingWorkflowCoordinator coordinator = new PatchingWorkflowCoordinator(store);
        var created = coordinator.createProposal("payments-prod", "conv-1", "task-1", "request-1");

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
                  "message": "approve workflow %s",
                  "taskContext": {
                    "conversationId": "conv-1",
                    "taskId": "task-1"
                  }
                }
                """.formatted(created.workflowId()));

        assertTrue(response.message().contains("Execution has been queued."));
        assertFalse(response.message().contains("Check status for workflow " + created.workflowId()));
        assertNotNull(response.metadata());
        assertTrue(response.metadata().actions().stream().anyMatch(a ->
                ("Check status for workflow " + created.workflowId()).equals(a.prompt())));
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
