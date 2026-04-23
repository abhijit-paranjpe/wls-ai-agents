package com.example.wls.agentic.ai;

import com.example.wls.agentic.workflow.WorkflowRecord;
import com.example.wls.agentic.workflow.WorkflowStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowSupervisorAgentTest {

    @Test
    void executeInvokesRuntimeAndPatchingStepsWhenDependenciesAreProvided() {
        DomainRuntimeAgent runtimeAgent = mock(DomainRuntimeAgent.class);
        PatchingAgent patchingAgent = mock(PatchingAgent.class);
        when(runtimeAgent.analyzeRequest(contains("Stop all servers")))
                .thenReturn("Stop initiated for host wlsoci12-wls-0 pid 111");
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 111 on host wlsoci12-wls-0")))
                .thenReturn("The async job with PID 111 on host wlsoci12-wls-0 has completed successfully.");
        when(runtimeAgent.analyzeRequest(contains("Confirm whether all servers in domain")))
                .thenReturn("All servers are fully stopped");
        when(patchingAgent.analyzeRequest(contains("Apply recommended patches")))
                .thenReturn("Apply initiated for host wlsoci12-wls-0 pid 222");
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 222 on host wlsoci12-wls-0")))
                .thenReturn("The async job with PID 222 on host wlsoci12-wls-0 has completed successfully.");
        when(runtimeAgent.analyzeRequest(contains("Start all servers")))
                .thenReturn("Start initiated for host wlsoci12-wls-0 pid 333");
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 333 on host wlsoci12-wls-0")))
                .thenReturn("The async job with PID 333 on host wlsoci12-wls-0 has completed successfully.");
        when(runtimeAgent.analyzeRequest(contains("are fully running now")))
                .thenReturn("All servers are fully running");
        when(patchingAgent.analyzeRequest(contains("Verify domain"))).thenReturn("Verification successful");

        WorkflowSupervisorAgent supervisor = new WorkflowSupervisorAgent(runtimeAgent, patchingAgent);
        supervisor.execute(workflow("payments-prod", "regular-request"));

        verify(runtimeAgent).analyzeRequest(contains("Stop all servers in domain payments-prod"));
        verify(patchingAgent).analyzeRequest(contains("Apply recommended patches for domain payments-prod"));
        verify(runtimeAgent).analyzeRequest(contains("Start all servers in domain payments-prod"));
        verify(patchingAgent).analyzeRequest(contains("Verify domain payments-prod"));
    }

    @Test
    void executeFailsWhenAnyStepReportsFailureText() {
        DomainRuntimeAgent runtimeAgent = mock(DomainRuntimeAgent.class);
        PatchingAgent patchingAgent = mock(PatchingAgent.class);
        when(runtimeAgent.analyzeRequest(contains("Stop all servers")))
                .thenReturn("Stop initiated for host wlsoci12-wls-0 pid 111");
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 111 on host wlsoci12-wls-0")))
                .thenReturn("The async job with PID 111 on host wlsoci12-wls-0 has completed successfully.");
        when(runtimeAgent.analyzeRequest(contains("Confirm whether all servers in domain")))
                .thenReturn("All servers are fully stopped");
        when(patchingAgent.analyzeRequest(contains("Apply recommended patches"))).thenReturn("failed to apply patches");

        WorkflowSupervisorAgent supervisor = new WorkflowSupervisorAgent(runtimeAgent, patchingAgent);
        assertThrows(IllegalStateException.class, () -> supervisor.execute(workflow("payments-prod", "regular-request")));
    }

    @Test
    void executeFailsWhenStopAsyncTrackingReportsFailure() {
        DomainRuntimeAgent runtimeAgent = mock(DomainRuntimeAgent.class);
        PatchingAgent patchingAgent = mock(PatchingAgent.class);
        when(runtimeAgent.analyzeRequest(contains("Stop all servers")))
                .thenReturn("Stop initiated for host wlsoci12-wls-0 pid 111");
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 111 on host wlsoci12-wls-0")))
                .thenReturn("Unable to track async job status for PID 111 on host wlsoci12-wls-0");

        WorkflowSupervisorAgent supervisor = new WorkflowSupervisorAgent(runtimeAgent, patchingAgent);
        assertThrows(IllegalStateException.class, () -> supervisor.execute(workflow("payments-prod", "regular-request")));
    }

    private static WorkflowRecord workflow(String domain, String requestSummary) {
        Instant now = Instant.now();
        return new WorkflowRecord(
                "wf-1",
                domain,
                WorkflowStatus.IN_EXECUTION,
                now,
                now,
                "conv-1",
                "task-1",
                requestSummary,
                null,
                null,
                null,
                null,
                List.of());
    }
}
