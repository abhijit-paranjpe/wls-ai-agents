package com.oracle.wls.agentic.ai;

import com.oracle.wls.agentic.workflow.WorkflowStateMutationService;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    private static final WorkflowExecutionPlan APPLY_PLAN = new WorkflowExecutionPlan(
            "apply-latest-patches-workflow",
            List.of(
                    new WorkflowExecutionStep(1, "initiate-stop-servers", "step1 %s", WorkflowStepAgentType.DOMAIN_RUNTIME, null),
                    new WorkflowExecutionStep(2, "monitor-stop-completion", "step2 %s", WorkflowStepAgentType.MONITORING, null),
                    new WorkflowExecutionStep(3, "apply-latest-patches", "step3 %s", WorkflowStepAgentType.PATCHING, null),
                    new WorkflowExecutionStep(4, "monitor-patch-completion", "step4 %s", WorkflowStepAgentType.MONITORING, "apply-latest-patches"),
                    new WorkflowExecutionStep(5, "initiate-start-servers", "step5 %s", WorkflowStepAgentType.DOMAIN_RUNTIME, null),
                    new WorkflowExecutionStep(6, "monitor-start-completion", "step6 %s", WorkflowStepAgentType.MONITORING, null),
                    new WorkflowExecutionStep(7, "verify-domain-patch-level", "step7 %s", WorkflowStepAgentType.PATCHING, null)));

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

        String response = agent.run("wf-1", "payments-prod", "execute", "start", APPLY_PLAN);

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

        String response = agent.run("wf-2", "payments-prod", "execute", "start", APPLY_PLAN);

        verify(patchingAgent, never()).analyzeRequest(anyString());
        assertTrue(response.contains("\"status\":\"failed\""));
    }

    @Test
    void monitorStepFailsFastWhenPriorStepLacksTrackingContext() {
        DomainRuntimeAgent domainRuntimeAgent = mock(DomainRuntimeAgent.class);
        MonitoringAgent monitoringAgent = mock(MonitoringAgent.class);
        PatchingAgent patchingAgent = mock(PatchingAgent.class);
        WorkflowStateMutationService workflowStateMutationService = mock(WorkflowStateMutationService.class);

        when(domainRuntimeAgent.analyzeRequest(anyString()))
                .thenReturn("""
                        {"status":"completed","operation":"stop-servers","domain":"payments-prod","message":"stop initiated"}
                        """);

        WorkflowExecutionSequenceAgent agent = new WorkflowExecutionSequenceAgent(
                domainRuntimeAgent,
                monitoringAgent,
                patchingAgent,
                workflowStateMutationService);

        String response = agent.run("wf-3", "payments-prod", "execute", "start", APPLY_PLAN);

        verify(monitoringAgent, never()).analyzeRequest(anyString());
        verify(patchingAgent, never()).analyzeRequest(anyString());
        verify(workflowStateMutationService).markStepFailedAndFailWorkflow(eq("wf-3"), eq("monitor-stop-completion"), anyString());
        assertTrue(response.contains("\"status\":\"failed\""));
        assertTrue(response.contains("Missing required async tracking context"));
    }
}
