package com.oracle.wls.agentic.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestRouterAgentTest {

    @Test
    void diagnosticIntentWithPidOnlyRdaRequestDoesNotActivatePatching() {
        boolean selected = RequestRouterAgent.activatePatching(
                RequestIntent.DIAGNOSTIC_TROUBLESHOOTING,
                "Get RDA report for wlsucm14c-wls-0 and pid 1736784");

        assertFalse(selected);
    }

    @Test
    void diagnosticIntentWithPidOnlyRdaRequestCanActivateDiagnostic() {
        boolean selected = RequestRouterAgent.activateDiagnostic(
                RequestIntent.DIAGNOSTIC_TROUBLESHOOTING,
                "Get RDA report for wlsucm14c-wls-0 and pid 1736784");

        assertTrue(selected);
    }
}
