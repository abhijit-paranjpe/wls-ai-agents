package com.example.wls.agentic.workflow;

import com.example.wls.agentic.ai.DomainRuntimeAgent;
import com.example.wls.agentic.ai.PatchingAgent;
import com.example.wls.agentic.workflow.AsyncExecutionService;
import com.example.wls.agentic.workflow.McpAgent;
import io.helidon.service.registry.Service;

@Service.Singleton
public class PatchExecutionService {

    private final DomainRuntimeAgent domainRuntimeAgent;
    private final PatchingAgent patchingAgent;
    private final AsyncExecutionService asyncExecutionService;

    @Service.Inject
    public PatchExecutionService(DomainRuntimeAgent domainRuntimeAgent,
                                 PatchingAgent patchingAgent,
                                 AsyncExecutionService asyncExecutionService) {
        this.domainRuntimeAgent = domainRuntimeAgent;
        this.patchingAgent = patchingAgent;
        this.asyncExecutionService = asyncExecutionService;
    }

    public PatchExecutionResult executeRecommendedPatchFlow(String domain) {
        // Safety guarantee: always stop servers before applying patches.
        String stopPrompt = """
                Execute the actual stop-servers operation now for all relevant WebLogic servers in domain '%s'.
                Use the MCP tools and return only factual execution results.
                If the stop operation cannot be completed, clearly say it failed.
                """.formatted(domain).trim();
        String applyPrompt = """
                Execute the actual patch-application operation now for domain '%s'.
                Use the MCP patching tools to apply the recommended/latest applicable patches.
                Do not only recommend or plan patches.
                Return only factual execution results.
                If no applicable patches need to be applied, say that explicitly.
                If patch application fails, clearly say it failed.
                """.formatted(domain).trim();
        String startPrompt = """
                Execute the actual start-servers operation now for all relevant WebLogic servers in domain '%s'.
                Use the MCP tools and return only factual execution results.
                If the start operation cannot be completed, clearly say it failed.
                """.formatted(domain).trim();
        String verifyPrompt = """
                Verify the current patch status now for domain '%s' using the MCP patching tools.
                Return only factual verification results about the final patch state.
                If verification fails, clearly say it failed.
                """.formatted(domain).trim();

        McpAgent domainAgent = domainRuntimeAgent::analyzeRequest;
        McpAgent patchingAgentWrapper = patchingAgent::analyzeRequest;

        String stopResult = asyncExecutionService.executeAndWait(stopPrompt, domainAgent);
        if (!isSuccessfulStopResult(stopResult)) {
            String blockedMessage = "Skipping patch apply/start/verify because stop-servers step did not complete successfully.";
            return new PatchExecutionResult(stopResult, blockedMessage, blockedMessage, blockedMessage);
        }

        String applyResult = asyncExecutionService.executeAndWait(applyPrompt, patchingAgentWrapper);
        String startResult = asyncExecutionService.executeAndWait(startPrompt, domainAgent);
        String verifyResult = asyncExecutionService.executeAndWait(verifyPrompt, patchingAgentWrapper);

        return new PatchExecutionResult(stopResult, applyResult, startResult, verifyResult);
    }

    public PatchExecutionResult executeRollbackPatchFlow(String domain) {
        String stopPrompt = """
                Execute the actual stop-servers operation now for all relevant WebLogic servers in domain '%s'.
                Use the MCP tools and return only factual execution results.
                If the stop operation cannot be completed, clearly say it failed.
                """.formatted(domain).trim();
        String rollbackPrompt = """
                Execute the actual patch-rollback operation now for domain '%s'.
                Use the MCP patching tools to rollback the latest applied patches.
                Do not only recommend or plan rollback.
                Return only factual execution results.
                If no applicable patches can be rolled back, say that explicitly.
                If rollback fails, clearly say it failed.
                """.formatted(domain).trim();
        String startPrompt = """
                Execute the actual start-servers operation now for all relevant WebLogic servers in domain '%s'.
                Use the MCP tools and return only factual execution results.
                If the start operation cannot be completed, clearly say it failed.
                """.formatted(domain).trim();
        String verifyPrompt = """
                Verify the current patch status now for domain '%s' using the MCP patching tools.
                Return only factual verification results about the final patch state.
                If verification fails, clearly say it failed.
                """.formatted(domain).trim();

        McpAgent domainAgent = domainRuntimeAgent::analyzeRequest;
        McpAgent patchingAgentWrapper = patchingAgent::analyzeRequest;

        String stopResult = asyncExecutionService.executeAndWait(stopPrompt, domainAgent);
        if (!isSuccessfulStopResult(stopResult)) {
            String blockedMessage = "Skipping patch rollback/start/verify because stop-servers step did not complete successfully.";
            return new PatchExecutionResult(stopResult, blockedMessage, blockedMessage, blockedMessage);
        }

        String rollbackResult = asyncExecutionService.executeAndWait(rollbackPrompt, patchingAgentWrapper);
        String startResult = asyncExecutionService.executeAndWait(startPrompt, domainAgent);
        String verifyResult = asyncExecutionService.executeAndWait(verifyPrompt, patchingAgentWrapper);

        return new PatchExecutionResult(stopResult, rollbackResult, startResult, verifyResult);
    }

    private static boolean isSuccessfulStopResult(String result) {
        if (result == null || result.isBlank()) {
            return false;
        }

        String lower = result.toLowerCase();
        if (lower.contains("[warning]")
                || lower.contains("in_progress")
                || lower.contains("in progress")
                || lower.contains("still in progress")
                || lower.contains("operation in progress")
                || lower.contains("status: in progress")
                || lower.contains("pidstate: running")
                || lower.contains("\"pidstate\":\"running\"")
                || lower.contains("timed out")
                || lower.contains("did not reach a terminal state")
                || lower.contains("failed")
                || lower.contains("error")
                || lower.contains("unable to")
                || lower.contains("could not")
                || lower.contains("cannot ")
                || lower.contains("exception")) {
            return false;
        }

        return true;
    }
}