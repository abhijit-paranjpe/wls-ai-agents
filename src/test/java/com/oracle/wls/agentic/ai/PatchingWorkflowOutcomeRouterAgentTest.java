package com.oracle.wls.agentic.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PatchingWorkflowOutcomeRouterAgentTest {

    @Test
    void routeReturnsSupportedOutcomes() {
        assertEquals("APPROVED", PatchingWorkflowOutcomeRouterAgent.route("approved"));
        assertEquals("REJECTED", PatchingWorkflowOutcomeRouterAgent.route("REJECTED"));
        assertEquals("REJECTED", PatchingWorkflowOutcomeRouterAgent.route("cancelled"));
    }

    @Test
    void routeFallsBackToRejectedForUnsupportedOrMissingValues() {
        assertEquals("REJECTED", PatchingWorkflowOutcomeRouterAgent.route("maybe"));
        assertEquals("REJECTED", PatchingWorkflowOutcomeRouterAgent.route("   "));
        assertEquals("REJECTED", PatchingWorkflowOutcomeRouterAgent.route(null));
    }
}