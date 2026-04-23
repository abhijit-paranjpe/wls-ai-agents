package com.example.wls.agentic.ai;

import com.example.wls.agentic.workflow.WorkflowRecord;
import io.helidon.service.registry.Service;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Phase 6 execution supervisor abstraction.
 *
 * For the POC this implementation is intentionally deterministic and lightweight:
 * it can be swapped later with a richer multi-step LangChain4j workflow.
 */
@Service.Singleton
public class WorkflowSupervisorAgent {

    private static final Logger LOGGER = Logger.getLogger(WorkflowSupervisorAgent.class.getName());
    private static final Pattern PID_PATTERN = Pattern.compile("(?i)\\bpid\\s*[:=]?\\s*(\\d+)\\b");
    private static final Pattern HOST_PATTERN = Pattern.compile("(?i)\\bhost\\s*[:=]?\\s*([A-Za-z0-9._-]+)\\b");
    private static final int ASYNC_TRACK_MAX_ATTEMPTS = 40;
    // Keep tracker polling sparse to avoid saturating LangChain4j concurrent tool-call limits.
    private static final long ASYNC_TRACK_SLEEP_MILLIS = 60_000L;

    private final DomainRuntimeAgent domainRuntimeAgent;
    private final PatchingAgent patchingAgent;

    public WorkflowSupervisorAgent() {
        this.domainRuntimeAgent = null;
        this.patchingAgent = null;
    }

    @Service.Inject
    public WorkflowSupervisorAgent(DomainRuntimeAgent domainRuntimeAgent,
                                   PatchingAgent patchingAgent) {
        this.domainRuntimeAgent = Objects.requireNonNull(domainRuntimeAgent, "domainRuntimeAgent must not be null");
        this.patchingAgent = Objects.requireNonNull(patchingAgent, "patchingAgent must not be null");
    }

    /**
     * Execute approved workflow steps.
     *
     * @param workflow workflow to execute
     * @throws IllegalStateException when a controlled failure is requested
     */
    public void execute(WorkflowRecord workflow) {
        Objects.requireNonNull(workflow, "workflow must not be null");
        LOGGER.log(Level.INFO,
                "Starting workflow execution. workflowId={0}, domain={1}, currentState={2}",
                new Object[]{workflow.workflowId(), workflow.domain(), workflow.currentState()});

        // Controlled test hook for failure-path validation.
        String summary = workflow.requestSummary();
        if (summary != null && summary.toUpperCase().contains("FORCE_FAIL")) {
            LOGGER.log(Level.WARNING,
                    "Controlled failure requested for workflowId={0}",
                    workflow.workflowId());
            throw new IllegalStateException("Controlled execution failure requested");
        }

        // Misconfiguration guard: execution must not proceed without concrete step executors.
        if (domainRuntimeAgent == null || patchingAgent == null) {
            LOGGER.log(Level.SEVERE,
                    "Workflow supervisor dependencies missing; failing workflowId={0}",
                    workflow.workflowId());
            throw new IllegalStateException("Workflow supervisor dependencies are not configured");
        }

        String domain = workflow.domain();
        LOGGER.log(Level.INFO, "[workflowId={0}] Step START: stop servers", workflow.workflowId());
        String stopResponse = domainRuntimeAgent.analyzeRequest(
                "Stop all servers in domain " + domain + ". Return factual runtime tool output only.");
        runOrThrow(stopResponse, "stop servers", workflow.workflowId());
        awaitAsyncCompletion("stop servers", workflow.workflowId(), stopResponse);
        LOGGER.log(Level.INFO, "[workflowId={0}] Step DONE: stop servers", workflow.workflowId());

        LOGGER.log(Level.INFO, "[workflowId={0}] Step START: apply patches", workflow.workflowId());
        String applyResponse = patchingAgent.analyzeRequest(
                "Apply recommended patches for domain " + domain + ". Execute tools and return factual results only.");
        runOrThrow(applyResponse, "apply patches", workflow.workflowId());
        awaitAsyncCompletion("apply patches", workflow.workflowId(), applyResponse);
        LOGGER.log(Level.INFO, "[workflowId={0}] Step DONE: apply patches", workflow.workflowId());

        LOGGER.log(Level.INFO, "[workflowId={0}] Step START: start servers", workflow.workflowId());
        String startResponse = domainRuntimeAgent.analyzeRequest(
                "Start all servers in domain " + domain + ". Return factual runtime tool output only.");
        runOrThrow(startResponse, "start servers", workflow.workflowId());
        awaitAsyncCompletion("start servers", workflow.workflowId(), startResponse);
        LOGGER.log(Level.INFO, "[workflowId={0}] Step DONE: start servers", workflow.workflowId());

        LOGGER.log(Level.INFO, "[workflowId={0}] Step START: verify patch level", workflow.workflowId());
        runOrThrow(patchingAgent.analyzeRequest(
                "Verify domain " + domain + " is on latest recommended patch level. Return factual verification output only."),
                "verify patch level",
                workflow.workflowId());
        LOGGER.log(Level.INFO, "[workflowId={0}] Step DONE: verify patch level", workflow.workflowId());
        LOGGER.log(Level.INFO, "Workflow execution completed. workflowId={0}", workflow.workflowId());
    }

    private static void runOrThrow(String response, String stepName, String workflowId) {
        if (response == null || response.isBlank()) {
            LOGGER.log(Level.SEVERE,
                    "[workflowId={0}] Step FAILED: {1}; empty response",
                    new Object[]{workflowId, stepName});
            throw new IllegalStateException("Workflow step returned no output: " + stepName);
        }
        String normalized = response.toLowerCase();
        if (normalized.contains("unable")
                || normalized.contains("failed")
                || normalized.contains("error")
                || normalized.contains("not found")) {
            LOGGER.log(Level.SEVERE,
                    "[workflowId={0}] Step FAILED: {1}; response={2}",
                    new Object[]{workflowId, stepName, response});
            throw new IllegalStateException("Workflow step failed: " + stepName + " - " + response);
        }
        LOGGER.log(Level.FINE,
                "[workflowId={0}] Step response ({1}): {2}",
                new Object[]{workflowId, stepName, response});
    }

    private void awaitAsyncCompletion(String stepName, String workflowId, String operationResponse) {
        String pid = extract(PID_PATTERN, operationResponse);
        String host = extract(HOST_PATTERN, operationResponse);
        if (pid == null || host == null) {
            LOGGER.log(Level.SEVERE,
                    "[workflowId={0}] Step FAILED: {1}; missing PID/host for async tracking. response={2}",
                    new Object[]{workflowId, stepName, operationResponse});
            throw new IllegalStateException("Workflow step did not provide async tracking identifiers: " + stepName);
        }

        for (int i = 1; i <= ASYNC_TRACK_MAX_ATTEMPTS; i++) {
            String trackPrompt = "Track async job status for PID " + pid + " on host " + host;
            String status = domainRuntimeAgent.analyzeRequest(trackPrompt);
            runOrThrow(status, stepName + " async tracking", workflowId);
            String normalized = status.toLowerCase();
            if (normalized.contains("completed successfully")) {
                LOGGER.log(Level.INFO,
                        "[workflowId={0}] Step async completion confirmed: {1} (pid={2}, host={3})",
                        new Object[]{workflowId, stepName, pid, host});
                return;
            }
            if (i < ASYNC_TRACK_MAX_ATTEMPTS) {
                try {
                    Thread.sleep(ASYNC_TRACK_SLEEP_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for async completion: " + stepName, e);
                }
            }
        }

        throw new IllegalStateException("Async step did not complete within timeout: " + stepName
                + " (pid=" + pid + ", host=" + host + ")");
    }

    private static String extract(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static void requireStepCompleted(String stepName, String workflowId, String verificationResponse) {
        runOrThrow(verificationResponse, stepName + " verification", workflowId);
        String normalized = verificationResponse.toLowerCase();
        boolean looksInProgress = normalized.contains("initiated")
                || normalized.contains("submitted")
                || normalized.contains("queued")
                || normalized.contains("in progress")
                || normalized.contains("running asynchronously")
                || normalized.contains("track")
                || normalized.contains("job id");
        if (looksInProgress) {
            LOGGER.log(Level.SEVERE,
                    "[workflowId={0}] Step FAILED: {1}; operation still in progress. verificationResponse={2}",
                    new Object[]{workflowId, stepName, verificationResponse});
            throw new IllegalStateException("Workflow step not yet complete: " + stepName + " - " + verificationResponse);
        }
        LOGGER.log(Level.INFO,
                "[workflowId={0}] Step verification passed: {1}",
                new Object[]{workflowId, stepName});
    }
}