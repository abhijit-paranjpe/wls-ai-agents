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
                .thenReturn("- patch-12345: recommended security update\n- patch-67890: latest PSU");
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

        assertTrue(response.message().contains("Completed steps: stop servers"));
        assertTrue(response.message().contains("Step in progress at failure: apply patches"));
        assertTrue(response.message().contains("Pending steps: start servers, verify patch level"));
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

        int firstIndex = response.message().indexOf("Step in progress: stop servers");
        int lastIndex = response.message().lastIndexOf("Step in progress: stop servers");
        assertTrue(firstIndex >= 0);
        assertEquals(firstIndex, lastIndex);
        assertTrue(response.message().contains("Pending steps: apply patches, start servers, verify patch level"));
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
