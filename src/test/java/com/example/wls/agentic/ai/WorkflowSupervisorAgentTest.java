package com.example.wls.agentic.ai;

import com.example.wls.agentic.workflow.WorkflowRecord;
import com.example.wls.agentic.workflow.WorkflowStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowSupervisorAgentTest {

    @Test
    void executeInvokesRuntimeAndPatchingStepsWhenDependenciesAreProvided() {
        DomainRuntimeAgent runtimeAgent = mock(DomainRuntimeAgent.class);
        PatchingAgent patchingAgent = mock(PatchingAgent.class);
        when(runtimeAgent.analyzeRequest(contains("Stop all servers")))
                .thenReturn("""
                        {"status":"started","operation":"stop-servers","domain":"payments-prod","async":true,
                         "host":"wlsoci12-wls-0","pid":"111","message":"Stop initiated"}
                        """);
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 111 on host wlsoci12-wls-0")))
                .thenReturn("""
                        {"status":"completed","operation":"track-async-job","domain":"payments-prod","async":false,
                         "host":"wlsoci12-wls-0","pid":"111","message":"Completed successfully"}
                        """);
        when(patchingAgent.analyzeRequest(contains("Apply recommended patches")))
                .thenReturn("""
                        {"status":"started","operation":"apply-recommended-patches","domain":"payments-prod","async":true,
                         "host":"wlsoci12-wls-0","pid":"222","message":"Patch apply started"}
                        """);
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 222 on host wlsoci12-wls-0")))
                .thenReturn("""
                        {"status":"completed","operation":"track-async-job","domain":"payments-prod","async":false,
                         "host":"wlsoci12-wls-0","pid":"222","message":"Completed successfully"}
                        """);
        when(runtimeAgent.analyzeRequest(contains("Start all servers")))
                .thenReturn("""
                        {"status":"started","operation":"start-servers","domain":"payments-prod","async":true,
                         "host":"wlsoci12-wls-0","pid":"333","message":"Start initiated"}
                        """);
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 333 on host wlsoci12-wls-0")))
                .thenReturn("""
                        {"status":"completed","operation":"track-async-job","domain":"payments-prod","async":false,
                         "host":"wlsoci12-wls-0","pid":"333","message":"Completed successfully"}
                        """);
        when(patchingAgent.analyzeRequest(contains("Verify domain"))).thenReturn("""
                {"status":"completed","operation":"verify-patch-level","domain":"payments-prod","async":false,
                 "host":"","pid":"","message":"Verification successful"}
                """);

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
                .thenReturn("""
                        {"status":"started","operation":"stop-servers","domain":"payments-prod","async":true,
                         "host":"wlsoci12-wls-0","pid":"111","message":"Stop initiated"}
                        """);
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 111 on host wlsoci12-wls-0")))
                .thenReturn("""
                        {"status":"completed","operation":"track-async-job","domain":"payments-prod","async":false,
                         "host":"wlsoci12-wls-0","pid":"111","message":"Completed successfully"}
                        """);
        when(patchingAgent.analyzeRequest(contains("Apply recommended patches"))).thenReturn("""
                {"status":"failed","operation":"apply-recommended-patches","domain":"payments-prod","async":false,
                 "host":"","pid":"","message":"failed to apply patches"}
                """);

        WorkflowSupervisorAgent supervisor = new WorkflowSupervisorAgent(runtimeAgent, patchingAgent);
        assertThrows(IllegalStateException.class, () -> supervisor.execute(workflow("payments-prod", "regular-request")));
    }

    @Test
    void executeFailsWhenStopAsyncTrackingReportsFailure() {
        DomainRuntimeAgent runtimeAgent = mock(DomainRuntimeAgent.class);
        PatchingAgent patchingAgent = mock(PatchingAgent.class);
        when(runtimeAgent.analyzeRequest(contains("Stop all servers")))
                .thenReturn("""
                        {"status":"started","operation":"stop-servers","domain":"payments-prod","async":true,
                         "host":"wlsoci12-wls-0","pid":"111","message":"Stop initiated"}
                        """);
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 111 on host wlsoci12-wls-0")))
                .thenReturn("""
                        {"status":"failed","operation":"track-async-job","domain":"payments-prod","async":false,
                         "host":"wlsoci12-wls-0","pid":"111","message":"Unable to track async job status"}
                        """);

        WorkflowSupervisorAgent supervisor = new WorkflowSupervisorAgent(runtimeAgent, patchingAgent);
        assertThrows(IllegalStateException.class, () -> supervisor.execute(workflow("payments-prod", "regular-request")));
    }

    @Test
    void executeFailsWhenStepReturnsInvalidJson() {
        DomainRuntimeAgent runtimeAgent = mock(DomainRuntimeAgent.class);
        PatchingAgent patchingAgent = mock(PatchingAgent.class);
        when(runtimeAgent.analyzeRequest(contains("Stop all servers"))).thenReturn("Stop initiated host h pid 1");

        WorkflowSupervisorAgent supervisor = new WorkflowSupervisorAgent(runtimeAgent, patchingAgent);
        assertThrows(IllegalStateException.class, () -> supervisor.execute(workflow("payments-prod", "regular-request")));
    }

    @Test
    void executeFailsWhenAsyncResponseMissingHostOrPid() {
        DomainRuntimeAgent runtimeAgent = mock(DomainRuntimeAgent.class);
        PatchingAgent patchingAgent = mock(PatchingAgent.class);
        when(runtimeAgent.analyzeRequest(contains("Stop all servers"))).thenReturn("""
                {"status":"started","operation":"stop-servers","domain":"payments-prod","async":true,
                 "host":"","pid":"111","message":"Stop initiated"}
                """);

        WorkflowSupervisorAgent supervisor = new WorkflowSupervisorAgent(runtimeAgent, patchingAgent);
        assertThrows(IllegalStateException.class, () -> supervisor.execute(workflow("payments-prod", "regular-request")));
    }

    @Test
    void executeExtractsHostPidFromJsonPayloadWhenTopLevelFieldsAreEmpty() {
        DomainRuntimeAgent runtimeAgent = mock(DomainRuntimeAgent.class);
        PatchingAgent patchingAgent = mock(PatchingAgent.class);

        when(runtimeAgent.analyzeRequest(contains("Stop all servers"))).thenReturn("""
                {
                  "status":"started",
                  "operation":"stop-servers",
                  "domain":"payments-prod",
                  "async":true,
                  "host":"",
                  "pid":"",
                  "message":"Stop initiated",
                  "details":{"host":"wlsoci12-wls-0","pid":"111"}
                }
                """);
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 111 on host wlsoci12-wls-0")))
                .thenReturn("""
                        {"status":"completed","operation":"track-async-job","domain":"payments-prod","async":false,
                         "host":"wlsoci12-wls-0","pid":"111","message":"Completed successfully"}
                        """);
        when(patchingAgent.analyzeRequest(contains("Apply recommended patches")))
                .thenReturn("""
                        {"status":"started","operation":"apply-recommended-patches","domain":"payments-prod","async":true,
                         "host":"wlsoci12-wls-0","pid":"222","message":"Patch apply started"}
                        """);
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 222 on host wlsoci12-wls-0")))
                .thenReturn("""
                        {"status":"completed","operation":"track-async-job","domain":"payments-prod","async":false,
                         "host":"wlsoci12-wls-0","pid":"222","message":"Completed successfully"}
                        """);
        when(runtimeAgent.analyzeRequest(contains("Start all servers")))
                .thenReturn("""
                        {"status":"started","operation":"start-servers","domain":"payments-prod","async":true,
                         "host":"wlsoci12-wls-0","pid":"333","message":"Start initiated"}
                        """);
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 333 on host wlsoci12-wls-0")))
                .thenReturn("""
                        {"status":"completed","operation":"track-async-job","domain":"payments-prod","async":false,
                         "host":"wlsoci12-wls-0","pid":"333","message":"Completed successfully"}
                        """);
        when(patchingAgent.analyzeRequest(contains("Verify domain"))).thenReturn("""
                {"status":"completed","operation":"verify-patch-level","domain":"payments-prod","async":false,
                 "host":"","pid":"","message":"Verification successful"}
                """);

        WorkflowSupervisorAgent supervisor = new WorkflowSupervisorAgent(runtimeAgent, patchingAgent);
        supervisor.execute(workflow("payments-prod", "regular-request"));

        verify(runtimeAgent).analyzeRequest(contains("Track async job status for PID 111 on host wlsoci12-wls-0"));
    }

    @Test
    void executeRecoversAsyncIdentifiersWhenInitialStopResponseMissesHostPid() {
        DomainRuntimeAgent runtimeAgent = mock(DomainRuntimeAgent.class);
        PatchingAgent patchingAgent = mock(PatchingAgent.class);

        when(runtimeAgent.analyzeRequest(contains("Stop all servers"))).thenReturn("""
                {"status":"started","operation":"stop-servers","domain":"payments-prod","async":true,
                 "host":"","pid":"","message":"Initiating stop operation for all servers in domain payments-prod."}
                """);
        when(runtimeAgent.analyzeRequest(contains("started asynchronously but returned empty host/pid")))
                .thenReturn("""
                        {"status":"started","operation":"stop-servers","domain":"payments-prod","async":true,
                         "host":"wlsoci12-wls-0","pid":"111","message":"Stop initiated"}
                        """);
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 111 on host wlsoci12-wls-0")))
                .thenReturn("""
                        {"status":"completed","operation":"track-async-job","domain":"payments-prod","async":false,
                         "host":"wlsoci12-wls-0","pid":"111","message":"Completed successfully"}
                        """);
        when(patchingAgent.analyzeRequest(contains("Apply recommended patches")))
                .thenReturn("""
                        {"status":"started","operation":"apply-recommended-patches","domain":"payments-prod","async":true,
                         "host":"wlsoci12-wls-0","pid":"222","message":"Patch apply started"}
                        """);
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 222 on host wlsoci12-wls-0")))
                .thenReturn("""
                        {"status":"completed","operation":"track-async-job","domain":"payments-prod","async":false,
                         "host":"wlsoci12-wls-0","pid":"222","message":"Completed successfully"}
                        """);
        when(runtimeAgent.analyzeRequest(contains("Start all servers")))
                .thenReturn("""
                        {"status":"started","operation":"start-servers","domain":"payments-prod","async":true,
                         "host":"wlsoci12-wls-0","pid":"333","message":"Start initiated"}
                        """);
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 333 on host wlsoci12-wls-0")))
                .thenReturn("""
                        {"status":"completed","operation":"track-async-job","domain":"payments-prod","async":false,
                         "host":"wlsoci12-wls-0","pid":"333","message":"Completed successfully"}
                        """);
        when(patchingAgent.analyzeRequest(contains("Verify domain"))).thenReturn("""
                {"status":"completed","operation":"verify-patch-level","domain":"payments-prod","async":false,
                 "host":"","pid":"","message":"Verification successful"}
                """);

        WorkflowSupervisorAgent supervisor = new WorkflowSupervisorAgent(runtimeAgent, patchingAgent);
        supervisor.execute(workflow("payments-prod", "regular-request"));

        verify(runtimeAgent, atLeastOnce()).analyzeRequest(argThat(prompt ->
                prompt != null && prompt.contains("started asynchronously but returned empty host/pid")));
        verify(runtimeAgent).analyzeRequest(contains("Track async job status for PID 111 on host wlsoci12-wls-0"));
    }

    @Test
    void executeFailsWhenStepReturnsUnsupportedStatus() {
        DomainRuntimeAgent runtimeAgent = mock(DomainRuntimeAgent.class);
        PatchingAgent patchingAgent = mock(PatchingAgent.class);
        when(runtimeAgent.analyzeRequest(contains("Stop all servers"))).thenReturn("""
                {"status":"unknown","operation":"stop-servers","domain":"payments-prod","async":true,
                 "host":"wlsoci12-wls-0","pid":"111","message":"Stop initiated"}
                """);

        WorkflowSupervisorAgent supervisor = new WorkflowSupervisorAgent(runtimeAgent, patchingAgent);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> supervisor.execute(workflow("payments-prod", "regular-request")));
        assertTrue(ex.getMessage().contains("unsupported status"));
    }

    @Test
    void executeAcceptsFencedJsonResponseForWorkflowStep() {
        DomainRuntimeAgent runtimeAgent = mock(DomainRuntimeAgent.class);
        PatchingAgent patchingAgent = mock(PatchingAgent.class);
        when(runtimeAgent.analyzeRequest(contains("Stop all servers"))).thenReturn("""
                ```json
                {"status":"started","operation":"stop-servers","domain":"payments-prod","async":true,
                 "host":"wlsoci12-wls-0","pid":"111","message":"Stop initiated"}
                ```
                """);
        when(runtimeAgent.analyzeRequest(contains("Track async job status for PID 111 on host wlsoci12-wls-0")))
                .thenReturn("""
                        {"status":"completed","operation":"track-async-job","domain":"payments-prod","async":false,
                         "host":"wlsoci12-wls-0","pid":"111","message":"Completed successfully"}
                        """);
        when(patchingAgent.analyzeRequest(contains("Apply recommended patches"))).thenReturn("""
                {"status":"failed","operation":"apply-recommended-patches","domain":"payments-prod","async":false,
                 "host":"","pid":"","message":"fail fast for test"}
                """);

        WorkflowSupervisorAgent supervisor = new WorkflowSupervisorAgent(runtimeAgent, patchingAgent);
        // Should parse fenced JSON for stop step, then fail intentionally at apply step.
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> supervisor.execute(workflow("payments-prod", "regular-request")));
        assertTrue(ex.getMessage().contains("apply patches"));
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
