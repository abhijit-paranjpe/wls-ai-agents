package com.example.wls.agentic.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PatchingWorkflowOutcomeRouterAgentTest {

    @Test
    void routeReturnsSupportedOutcomes() {
        assertEquals("APPROVED", PatchingWorkflowOutcomeRouterAgent.route("approved"));
        assertEquals("REJECTED", PatchingWorkflowOutcomeRouterAgent.route("REJECTED"));
        assertEquals("CANCELLED", PatchingWorkflowOutcomeRouterAgent.route("cancelled"));
    }

    @Test
    void routeFallsBackToCancelledForUnsupportedOrMissingValues() {
        assertEquals("CANCELLED", PatchingWorkflowOutcomeRouterAgent.route("maybe"));
        assertEquals("CANCELLED", PatchingWorkflowOutcomeRouterAgent.route("   "));
        assertEquals("CANCELLED", PatchingWorkflowOutcomeRouterAgent.route(null));
    }
}