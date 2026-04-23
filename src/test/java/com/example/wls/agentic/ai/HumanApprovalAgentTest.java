package com.example.wls.agentic.ai;

import com.example.wls.agentic.workflow.ApprovalDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HumanApprovalAgentTest {

    @Test
    void toOutcomeMapsApprovalDecisionsToExpectedLabels() {
        assertEquals("APPROVED", HumanApprovalAgent.toOutcome(ApprovalDecision.APPROVE));
        assertEquals("REJECTED", HumanApprovalAgent.toOutcome(ApprovalDecision.REJECT));
        assertEquals("CANCELLED", HumanApprovalAgent.toOutcome(ApprovalDecision.CANCEL));
    }

    @Test
    void toOutcomeDefaultsNullToCancelled() {
        assertEquals("CANCELLED", HumanApprovalAgent.toOutcome(null));
    }
}