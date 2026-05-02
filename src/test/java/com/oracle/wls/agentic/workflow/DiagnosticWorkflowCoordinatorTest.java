package com.oracle.wls.agentic.workflow;

import com.oracle.wls.agentic.ai.DiagnosticAgent;
import com.oracle.wls.agentic.ai.MonitoringAgent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiagnosticWorkflowCoordinatorTest {

    @Test
    void startWorkflowPassesStructuredTrackingContextToMonitoring() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        WorkflowStateMutationService mutationService = new WorkflowStateMutationService(store);
        DiagnosticAgent diagnosticAgent = mock(DiagnosticAgent.class);
        MonitoringAgent monitoringAgent = mock(MonitoringAgent.class);

        when(diagnosticAgent.analyzeRequest(anyString()))
                .thenReturn(
                        "{\"status\":\"running\",\"operation\":\"run-rda-report\",\"domain\":\"payments-prod\",\"hostPids\":{\"host1\":\"123\"},\"message\":\"started\"}",
                        "Report URL: https://reports.example.com/rda-1.zip",
                        "RDA summary line\nMore detail");
        when(monitoringAgent.analyzeRequest(anyString()))
                .thenReturn("{\"status\":\"completed\",\"operation\":\"track-async-job\",\"domain\":\"payments-prod\",\"hostPids\":{\"host1\":\"123\"},\"message\":\"completed\"}");

        DiagnosticWorkflowCoordinator coordinator = new DiagnosticWorkflowCoordinator(
                store,
                mutationService,
                diagnosticAgent,
                monitoringAgent,
                new DirectExecutorService());

        WorkflowRecord created = coordinator.startRdaDiagnosticWorkflow("payments-prod", "conv-1", "task-1", "Create report");
        WorkflowRecord updated = coordinator.getByWorkflowId(created.workflowId()).orElseThrow();

        assertEquals(WorkflowStatus.COMPLETED, updated.currentState());
        assertEquals(3, updated.steps().size());
        assertTrue(updated.steps().stream().allMatch(step -> step.state() == WorkflowStepStatus.COMPLETED));

        verify(monitoringAgent).analyzeRequest(org.mockito.ArgumentMatchers.argThat(prompt ->
                prompt != null
                        && prompt.contains("targetDomain: payments-prod")
                        && prompt.contains("lastResponse:")
                        && prompt.contains("\"hostPids\":{\"host1\":\"123\"}")));
    }

    @Test
    void startWorkflowFailsEarlyWhenRunStepDoesNotReturnTrackingContext() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();
        WorkflowStateMutationService mutationService = new WorkflowStateMutationService(store);
        DiagnosticAgent diagnosticAgent = mock(DiagnosticAgent.class);
        MonitoringAgent monitoringAgent = mock(MonitoringAgent.class);

        when(diagnosticAgent.analyzeRequest(anyString()))
                .thenReturn("{\"status\":\"running\",\"operation\":\"run-rda-report\",\"domain\":\"payments-prod\",\"hostPids\":{},\"message\":\"started\"}");

        DiagnosticWorkflowCoordinator coordinator = new DiagnosticWorkflowCoordinator(
                store,
                mutationService,
                diagnosticAgent,
                monitoringAgent,
                new DirectExecutorService());

        WorkflowRecord created = coordinator.startRdaDiagnosticWorkflow("payments-prod", "conv-1", "task-1", "Create report");
        WorkflowRecord updated = coordinator.getByWorkflowId(created.workflowId()).orElseThrow();

        assertEquals(WorkflowStatus.FAILED, updated.currentState());
        assertTrue(updated.failureReason().contains("required async tracking context"));
        assertEquals(List.of("run rda diagnostic report"), updated.steps().stream().map(WorkflowStepRecord::name).toList());

        verify(monitoringAgent, never()).analyzeRequest(anyString());
    }

    private static final class DirectExecutorService extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
