package com.example.wls.agentic.ai;

import com.example.wls.agentic.workflow.WorkflowStateMutationService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowExecutionSequenceAgentTest {

    @Test
    void monitorStepPollsUntilCompletedBeforeAdvancing() {
        DomainRuntimeAgent domainRuntimeAgent = mock(DomainRuntimeAgent.class);
        MonitoringAgent monitoringAgent = mock(MonitoringAgent.class);
        PatchingAgent patchingAgent = mock(PatchingAgent.class);
        WorkflowStateMutationService workflowStateMutationService = mock(WorkflowStateMutationService.class);

        when(domainRuntimeAgent.analyzeRequest(anyString()))
                .thenReturn("""
                        {"status":"completed","operation":"stop-servers","domain":"payments-prod","hostPids":{"host1":"111"},"message":"stop initiated"}
                        """)
                .thenReturn("""
                        {"status":"completed","operation":"start-servers","domain":"payments-prod","hostPids":{"host1":"111"},"message":"start initiated"}
                        """);

        when(monitoringAgent.analyzeRequest(anyString()))
                .thenReturn("""
                        {"status":"completed","operation":"track-async-job","domain":"payments-prod","hostPids":{"host1":"111"},"message":"done"}
                        """)
                .thenReturn("""
                        {"status":"completed","operation":"track-async-job","domain":"payments-prod","hostPids":{"host1":"222"},"message":"patch done"}
                        """)
                .thenReturn("""
                        {"status":"completed","operation":"track-async-job","domain":"payments-prod","hostPids":{"host1":"333"},"message":"start done"}
                        """);

        when(patchingAgent.analyzeRequest(anyString()))
                .thenReturn("""
                        {"status":"completed","operation":"apply-recommended-patches","domain":"payments-prod","tracking":{"host1":"222"},"message":"patch started"}
                        """)
                .thenReturn("""
                        {"status":"completed","operation":"verify-patch-level","domain":"payments-prod","message":"verified"}
                        """);

        WorkflowExecutionSequenceAgent agent = new WorkflowExecutionSequenceAgent(
                domainRuntimeAgent,
                monitoringAgent,
                patchingAgent,
                workflowStateMutationService);

        String response = agent.run("wf-1", "payments-prod", "execute", "start");

        verify(monitoringAgent, times(3)).analyzeRequest(anyString());
        verify(patchingAgent, times(2)).analyzeRequest(anyString());
        verify(workflowStateMutationService, times(1))
                .markStepCompleted(eq("wf-1"), eq("monitor-patch-completion"), anyString());
        verify(workflowStateMutationService, times(1))
                .markStepCompleted(eq("wf-1"), eq("apply-latest-patches"), eq("patch done"));

        var order = inOrder(domainRuntimeAgent, monitoringAgent, patchingAgent);
        order.verify(domainRuntimeAgent).analyzeRequest(anyString());
        order.verify(monitoringAgent).analyzeRequest(anyString());
        order.verify(patchingAgent).analyzeRequest(anyString());

        assertTrue(response.contains("\"status\":\"completed\""));
    }

    @Test
    void failedMonitorStepHaltsWorkflowAndSkipsPatchApply() {
        DomainRuntimeAgent domainRuntimeAgent = mock(DomainRuntimeAgent.class);
        MonitoringAgent monitoringAgent = mock(MonitoringAgent.class);
        PatchingAgent patchingAgent = mock(PatchingAgent.class);
        WorkflowStateMutationService workflowStateMutationService = mock(WorkflowStateMutationService.class);

        when(domainRuntimeAgent.analyzeRequest(anyString()))
                .thenReturn("""
                        {"status":"completed","operation":"stop-servers","domain":"payments-prod","hostPids":{"host1":"111"},"message":"stop initiated"}
                        """);

        when(monitoringAgent.analyzeRequest(anyString()))
                .thenReturn("""
                        {"status":"failed","operation":"track-async-job","domain":"payments-prod","hostPids":{"host1":"111"},"message":"stop failed"}
                        """);

        WorkflowExecutionSequenceAgent agent = new WorkflowExecutionSequenceAgent(
                domainRuntimeAgent,
                monitoringAgent,
                patchingAgent,
                workflowStateMutationService);

        String response = agent.run("wf-2", "payments-prod", "execute", "start");

        verify(patchingAgent, never()).analyzeRequest(anyString());
        assertTrue(response.contains("\"status\":\"failed\""));
    }
}
