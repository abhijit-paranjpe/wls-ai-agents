package com.example.wls.agentic.ai;

import com.example.wls.agentic.workflow.PatchingWorkflowStateKeys;
import dev.langchain4j.agentic.declarative.Output;
import dev.langchain4j.service.V;
import io.helidon.integrations.langchain4j.Ai;

@Ai.Agent("patching-workflow-outcome-router")
public interface PatchingWorkflowOutcomeRouterAgent {

    @Output
    static String route(@V(PatchingWorkflowStateKeys.APPROVAL_DECISION_KEY) String decision) {
        if (decision == null || decision.isBlank()) {
            return "REJECTED";
        }
        String normalized = decision.trim().toUpperCase();
        return switch (normalized) {
            case "APPROVED", "REJECTED" -> normalized;
            case "CANCELLED" -> "REJECTED";
            default -> "REJECTED";
        };
    }
}