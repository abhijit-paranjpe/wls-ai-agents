package com.oracle.wls.agentic.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowResponseContractTest {

    @Test
    void extractStatusPrefersJsonStatus() {
        String response = "{\"status\":\"completed\",\"message\":\"ok\"}";
        assertEquals("completed", WorkflowResponseContract.extractStatus(response));
    }

    @Test
    void hasTrackingContextRequiresHostPidsAndDomainSignal() {
        assertTrue(WorkflowResponseContract.hasTrackingContext(
                "{\"status\":\"running\",\"domain\":\"payments-prod\",\"hostPids\":{\"host1\":\"123\"}}"));
        assertFalse(WorkflowResponseContract.hasTrackingContext(
                "{\"status\":\"running\",\"domain\":\"payments-prod\",\"hostPids\":{}}"));
    }

    @Test
    void trackingContextMissingFailureResponseUsesExpectedContract() {
        String failure = WorkflowResponseContract.trackingContextMissingFailureResponse("payments-prod", "monitor-stop-completion");
        assertTrue(failure.contains("\"status\":\"failed\""));
        assertTrue(failure.contains("\"operation\":\"track-async-job\""));
        assertTrue(failure.contains("payments-prod"));
        assertTrue(failure.contains("monitor-stop-completion"));
    }

    @Test
    void composePromptWithWorkflowContextBuildsExpectedShape() {
        String prompt = WorkflowResponseContract.composePromptWithWorkflowContext(
                "step question",
                "instruction",
                "payments-prod",
                "{\"status\":\"running\"}");

        assertTrue(prompt.contains("step question"));
        assertTrue(prompt.contains("Workflow instruction: instruction"));
        assertTrue(prompt.contains("targetDomain: payments-prod"));
        assertTrue(prompt.contains("lastResponse: {\"status\":\"running\"}"));
    }

    @Test
    void composePromptWithMonitoringContextBuildsExpectedShape() {
        String prompt = WorkflowResponseContract.composePromptWithMonitoringContext(
                "monitor question",
                "payments-prod",
                "{\"status\":\"running\"}");

        assertTrue(prompt.contains("monitor question"));
        assertTrue(prompt.contains("targetDomain: payments-prod"));
        assertTrue(prompt.contains("lastResponse: {\"status\":\"running\"}"));
    }
}
