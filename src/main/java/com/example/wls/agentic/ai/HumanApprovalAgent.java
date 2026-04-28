package com.example.wls.agentic.ai;

import com.example.wls.agentic.workflow.ApprovalDecision;
import com.example.wls.agentic.workflow.PatchingWorkflowStateKeys;
import com.example.wls.agentic.workflow.WorkflowApprovalSemaphore;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.HumanInTheLoop;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;
import io.helidon.integrations.langchain4j.Ai;

import java.util.concurrent.CancellationException;

@Ai.Agent("human-approval-agent")
public interface HumanApprovalAgent {

    @HumanInTheLoop(description = "Collect human approval for patch workflow",
            outputKey = PatchingWorkflowStateKeys.APPROVAL_DECISION_KEY)
    @Agent(value = "Human approver for patch workflow", outputKey = PatchingWorkflowStateKeys.APPROVAL_DECISION_KEY)
    static String await(@MemoryId String workflowId,
                        @V("approvalSemaphore") WorkflowApprovalSemaphore approvalSemaphore,
                        @V("proposalSummary") String proposalSummary) {
        try {
            ApprovalDecision decision = approvalSemaphore.awaitDecision(workflowId);
            return toOutcome(decision);
        } catch (CancellationException ignored) {
            return "REJECTED";
        }
    }

    static String toOutcome(ApprovalDecision decision) {
        if (decision == null) {
            return "REJECTED";
        }
        return switch (decision) {
            case APPROVE -> "APPROVED";
            case REJECT -> "REJECTED";
            case CANCEL -> "REJECTED";
        };
    }
}