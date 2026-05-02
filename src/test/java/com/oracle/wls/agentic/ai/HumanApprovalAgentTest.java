package com.oracle.wls.agentic.ai;

import com.oracle.wls.agentic.workflow.ApprovalDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HumanApprovalAgentTest {

    @Test
    void toOutcomeMapsApprovalDecisionsToExpectedLabels() {
        assertEquals("APPROVED", HumanApprovalAgent.toOutcome(ApprovalDecision.APPROVE));
        assertEquals("REJECTED", HumanApprovalAgent.toOutcome(ApprovalDecision.REJECT));
        assertEquals("REJECTED", HumanApprovalAgent.toOutcome(ApprovalDecision.CANCEL));
    }

    @Test
    void toOutcomeDefaultsNullToRejected() {
        assertEquals("REJECTED", HumanApprovalAgent.toOutcome(null));
    }
}