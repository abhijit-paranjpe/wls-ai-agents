package com.example.wls.agentic.ai;

import com.example.wls.agentic.dto.WorkflowOperationResponse;
import com.example.wls.agentic.workflow.WorkflowRecord;
import com.example.wls.agentic.workflow.WorkflowStateStore;
import com.example.wls.agentic.workflow.WorkflowStatus;
import com.example.wls.agentic.workflow.WorkflowStepRecord;
import io.helidon.service.registry.Service;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private static final int ASYNC_TRACK_MAX_ATTEMPTS = 40;
    private static final int PARSE_RETRY_MAX_ATTEMPTS = 2;
    private static final int ASYNC_IDENTIFIER_RECOVERY_ATTEMPTS = 2;
    private static final Pattern PID_PATTERN = Pattern.compile("(?i)\\bpid\\s*[:=]?\\s*(\\d+)\\b");
    private static final Pattern HOST_PATTERN = Pattern.compile("(?i)\\bhost\\s*[:=]?\\s*([A-Za-z0-9._-]+)\\b");
    private static final Pattern JSON_PID_PATTERN = Pattern.compile("(?i)\"pid\"\\s*:\\s*\"?(\\d+)\"?");
    private static final Pattern JSON_HOST_PATTERN = Pattern.compile("(?i)\"host\"\\s*:\\s*\"([A-Za-z0-9._-]+)\"");
    // Keep tracker polling sparse to avoid saturating LangChain4j concurrent tool-call limits.
    private static final long ASYNC_TRACK_SLEEP_MILLIS = 60_000L;

    private final DomainRuntimeAgent domainRuntimeAgent;
    private final PatchingAgent patchingAgent;
    private final WorkflowStateStore workflowStateStore;

    public WorkflowSupervisorAgent() {
        this.domainRuntimeAgent = null;
        this.patchingAgent = null;
        this.workflowStateStore = null;
    }

    @Service.Inject
    public WorkflowSupervisorAgent(DomainRuntimeAgent domainRuntimeAgent,
                                   PatchingAgent patchingAgent,
                                   WorkflowStateStore workflowStateStore) {
        this.domainRuntimeAgent = Objects.requireNonNull(domainRuntimeAgent, "domainRuntimeAgent must not be null");
        this.patchingAgent = Objects.requireNonNull(patchingAgent, "patchingAgent must not be null");
        this.workflowStateStore = Objects.requireNonNull(workflowStateStore, "workflowStateStore must not be null");
    }

    public WorkflowSupervisorAgent(DomainRuntimeAgent domainRuntimeAgent,
                                   PatchingAgent patchingAgent) {
        this.domainRuntimeAgent = Objects.requireNonNull(domainRuntimeAgent, "domainRuntimeAgent must not be null");
        this.patchingAgent = Objects.requireNonNull(patchingAgent, "patchingAgent must not be null");
        this.workflowStateStore = null;
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

        WorkflowRecord current = workflow;
        String domain = current.domain();

        current = updateStepState(current, "stop servers", WorkflowStatus.IN_EXECUTION, "Stop servers initiated", false);
        LOGGER.log(Level.INFO, "[workflowId={0}] Step START: stop servers", workflow.workflowId());
        String stopResponse = domainRuntimeAgent.analyzeRequest(workflowJsonPrompt(
                "Stop all servers in domain " + domain + ". Return factual runtime tool output only."));
        requireNonFailed(parseOperationResponseWithIdentifierRecovery(stopResponse, "stop servers", workflow.workflowId()),
                "stop servers", workflow.workflowId());
        awaitAsyncCompletion("stop servers", workflow.workflowId(), stopResponse);
        current = updateStepState(current, "stop servers", WorkflowStatus.COMPLETED, "Stop servers completed", true);
        LOGGER.log(Level.INFO, "[workflowId={0}] Step DONE: stop servers", workflow.workflowId());

        current = updateStepState(current, "apply patches", WorkflowStatus.IN_EXECUTION, "Apply patches initiated", false);
        LOGGER.log(Level.INFO, "[workflowId={0}] Step START: apply patches", workflow.workflowId());
        String applyResponse = patchingAgent.analyzeRequest(workflowJsonPrompt(
                "Apply recommended patches for domain " + domain + ". Execute tools and return factual results only."));
        requireNonFailed(parseOperationResponseWithIdentifierRecovery(applyResponse, "apply patches", workflow.workflowId()),
                "apply patches", workflow.workflowId());
        awaitAsyncCompletion("apply patches", workflow.workflowId(), applyResponse);
        current = updateStepState(current, "apply patches", WorkflowStatus.COMPLETED, "Apply patches completed", true);
        LOGGER.log(Level.INFO, "[workflowId={0}] Step DONE: apply patches", workflow.workflowId());

        current = updateStepState(current, "start servers", WorkflowStatus.IN_EXECUTION, "Start servers initiated", false);
        LOGGER.log(Level.INFO, "[workflowId={0}] Step START: start servers", workflow.workflowId());
        String startResponse = domainRuntimeAgent.analyzeRequest(workflowJsonPrompt(
                "Start all servers in domain " + domain + ". Return factual runtime tool output only."));
        requireNonFailed(parseOperationResponseWithIdentifierRecovery(startResponse, "start servers", workflow.workflowId()),
                "start servers", workflow.workflowId());
        awaitAsyncCompletion("start servers", workflow.workflowId(), startResponse);
        current = updateStepState(current, "start servers", WorkflowStatus.COMPLETED, "Start servers completed", true);
        LOGGER.log(Level.INFO, "[workflowId={0}] Step DONE: start servers", workflow.workflowId());

        current = updateStepState(current, "verify patch level", WorkflowStatus.IN_EXECUTION, "Verify patch level initiated", false);
        LOGGER.log(Level.INFO, "[workflowId={0}] Step START: verify patch level", workflow.workflowId());
        requireStepCompleted(parseOperationResponse(patchingAgent.analyzeRequest(workflowJsonPrompt(
                "Verify domain " + domain + " is on latest recommended patch level. Return factual verification output only.")),
                "verify patch level",
                workflow.workflowId()),
                "verify patch level",
                workflow.workflowId());
        updateStepState(current, "verify patch level", WorkflowStatus.COMPLETED, "Verify patch level completed", true);
        LOGGER.log(Level.INFO, "[workflowId={0}] Step DONE: verify patch level", workflow.workflowId());
        LOGGER.log(Level.INFO, "Workflow execution completed. workflowId={0}", workflow.workflowId());
    }

    private WorkflowRecord updateStepState(WorkflowRecord current,
                                           String stepName,
                                           WorkflowStatus stepState,
                                           String details,
                                           boolean setEndedAt) {
        if (current == null || workflowStateStore == null) {
            return current;
        }

        Instant now = Instant.now();
        List<WorkflowStepRecord> existingSteps = current.steps() == null ? List.of() : current.steps();
        List<WorkflowStepRecord> updatedSteps = new ArrayList<>(existingSteps.size() + 1);

        boolean replaced = false;
        for (WorkflowStepRecord step : existingSteps) {
            if (step != null && stepName.equalsIgnoreCase(step.name())) {
                updatedSteps.add(new WorkflowStepRecord(
                        step.stepId(),
                        step.name(),
                        stepState,
                        step.startedAt() == null ? now : step.startedAt(),
                        setEndedAt ? now : null,
                        details));
                replaced = true;
            } else {
                updatedSteps.add(step);
            }
        }

        if (!replaced) {
            updatedSteps.add(new WorkflowStepRecord(
                    stepName,
                    stepName,
                    stepState,
                    now,
                    setEndedAt ? now : null,
                    details));
        }

        WorkflowRecord updated = new WorkflowRecord(
                current.workflowId(),
                current.domain(),
                current.currentState(),
                current.createdAt(),
                now,
                current.conversationId(),
                current.taskId(),
                current.requestSummary(),
                current.approvalDecision(),
                current.approvalDecisionAt(),
                current.approvalChannel(),
                current.failureReason(),
                List.copyOf(updatedSteps));

        return workflowStateStore.update(updated);
    }

    private static WorkflowOperationResponse parseOperationResponse(String response,
                                                                    String stepName,
                                                                    String workflowId) {
        if (response == null || response.isBlank()) {
            LOGGER.log(Level.SEVERE,
                    "[workflowId={0}] Step FAILED: {1}; empty response",
                    new Object[]{workflowId, stepName});
            throw new IllegalStateException("Workflow step returned no output: " + stepName);
        }

        final JsonObject object = parseJsonWithRetry(response, stepName, workflowId);

        WorkflowOperationResponse operation = new WorkflowOperationResponse(
                getString(object, "status"),
                getString(object, "operation"),
                getString(object, "domain"),
                getBoolean(object, "async"),
                getString(object, "host"),
                getString(object, "pid"),
                getString(object, "message"));

        operation = normalizeOperationResponse(operation, stepName, response);
        operation = enrichAsyncIdentifiers(operation, response);

        validateOperationResponse(operation, stepName, workflowId, response);
        LOGGER.log(Level.FINE,
                "[workflowId={0}] Step response ({1}): {2}",
                new Object[]{workflowId, stepName, response});
        return operation;
    }

    private static void validateOperationResponse(WorkflowOperationResponse operation,
                                                  String stepName,
                                                  String workflowId,
                                                  String rawResponse) {
        if (isBlank(operation.status()) || isBlank(operation.operation()) || operation.async() == null
                || isBlank(operation.message())) {
            LOGGER.log(Level.SEVERE,
                    "[workflowId={0}] Step FAILED: {1}; missing required JSON fields. response={2}",
                    new Object[]{workflowId, stepName, rawResponse});
            throw new IllegalStateException("Workflow step response missing required JSON fields: " + stepName);
        }

        String status = operation.status().toLowerCase(Locale.ROOT);
        if (!("started".equals(status) || "running".equals(status)
                || "completed".equals(status) || "failed".equals(status))) {
            LOGGER.log(Level.SEVERE,
                    "[workflowId={0}] Step FAILED: {1}; unsupported status={2}. response={3}",
                    new Object[]{workflowId, stepName, operation.status(), rawResponse});
            throw new IllegalStateException("Workflow step response has unsupported status: " + stepName
                    + " - " + operation.status());
        }

        if (operation.async() && isTrackingOrExecutionStep(stepName)
                && (isBlank(operation.host()) || isBlank(operation.pid()))) {
            LOGGER.log(Level.SEVERE,
                    "[workflowId={0}] Step FAILED: {1}; async response missing host/pid. response={2}",
                    new Object[]{workflowId, stepName, rawResponse});
            throw new IllegalStateException("Workflow step async response missing host/pid: " + stepName);
        }
    }

    private static void requireNonFailed(WorkflowOperationResponse response, String stepName, String workflowId) {
        if ("failed".equalsIgnoreCase(response.status())) {
            LOGGER.log(Level.SEVERE,
                    "[workflowId={0}] Step FAILED: {1}; responseMessage={2}",
                    new Object[]{workflowId, stepName, response.message()});
            throw new IllegalStateException("Workflow step failed: " + stepName + " - " + response.message());
        }
    }

    private void awaitAsyncCompletion(String stepName, String workflowId, String operationResponse) {
        WorkflowOperationResponse operation = parseOperationResponseWithIdentifierRecovery(operationResponse, stepName, workflowId);
        requireNonFailed(operation, stepName, workflowId);
        if (!Boolean.TRUE.equals(operation.async())) {
            return;
        }
        String pid = operation.pid();
        String host = operation.host();
        if (isBlank(pid) || isBlank(host)) {
            LOGGER.log(Level.SEVERE,
                    "[workflowId={0}] Step FAILED: {1}; missing PID/host for async tracking. response={2}",
                    new Object[]{workflowId, stepName, operationResponse});
            throw new IllegalStateException("Workflow step did not provide async tracking identifiers: " + stepName);
        }

        for (int i = 1; i <= ASYNC_TRACK_MAX_ATTEMPTS; i++) {
            String trackPrompt = "Track async job status for PID " + pid + " on host " + host;
            WorkflowOperationResponse tracking = parseOperationResponse(
                    domainRuntimeAgent.analyzeRequest(workflowJsonPrompt(trackPrompt)),
                    stepName + " async tracking",
                    workflowId);
            requireNonFailed(tracking, stepName + " async tracking", workflowId);
            if ("completed".equalsIgnoreCase(tracking.status())) {
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

    private WorkflowOperationResponse parseOperationResponseWithIdentifierRecovery(String response,
                                                                                  String stepName,
                                                                                  String workflowId) {
        try {
            return parseOperationResponse(response, stepName, workflowId);
        } catch (IllegalStateException ex) {
            if (!isAsyncMissingHostPidError(ex) || !isTrackingOrExecutionStep(stepName)) {
                throw ex;
            }
            LOGGER.log(Level.WARNING,
                    "[workflowId={0}] Step {1} missing async host/pid. Attempting identifier recovery.",
                    new Object[]{workflowId, stepName});
        }

        String latest = response;
        for (int attempt = 1; attempt <= ASYNC_IDENTIFIER_RECOVERY_ATTEMPTS; attempt++) {
            String prompt = workflowJsonPrompt(
                    "The previous workflow step '" + stepName + "' started asynchronously but returned empty host/pid. "
                            + "Re-check runtime tool output for that in-flight operation and return strict JSON including host and pid.");
            latest = domainRuntimeAgent.analyzeRequest(prompt);
            WorkflowOperationResponse recovered = parseOperationResponse(latest, stepName, workflowId);
            if (!Boolean.TRUE.equals(recovered.async()) || (!isBlank(recovered.host()) && !isBlank(recovered.pid()))) {
                return recovered;
            }
            LOGGER.log(Level.WARNING,
                    "[workflowId={0}] Step {1} recovery attempt {2} still missing host/pid",
                    new Object[]{workflowId, stepName, attempt});
        }

        return parseOperationResponse(latest, stepName, workflowId);
    }

    private static boolean isAsyncMissingHostPidError(IllegalStateException ex) {
        return ex != null
                && ex.getMessage() != null
                && ex.getMessage().contains("async response missing host/pid");
    }

    private static void requireStepCompleted(WorkflowOperationResponse verificationResponse,
                                             String stepName,
                                             String workflowId) {
        requireNonFailed(verificationResponse, stepName + " verification", workflowId);
        if (!"completed".equalsIgnoreCase(verificationResponse.status())) {
            LOGGER.log(Level.SEVERE,
                    "[workflowId={0}] Step FAILED: {1}; operation not completed. verificationStatus={2}, verificationMessage={3}",
                    new Object[]{workflowId, stepName, verificationResponse.status(), verificationResponse.message()});
            throw new IllegalStateException("Workflow step not yet complete: " + stepName + " - "
                    + verificationResponse.message());
        }
        LOGGER.log(Level.INFO,
                "[workflowId={0}] Step verification passed: {1}",
                new Object[]{workflowId, stepName});
    }

    private static String extractJsonObject(String response) {
        String trimmed = response == null ? "" : response.trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0 && firstNewline + 1 < trimmed.length() - 3) {
                trimmed = trimmed.substring(firstNewline + 1, trimmed.length() - 3).trim();
            }
        }
        return trimmed;
    }

    private static JsonObject parseJsonWithRetry(String response, String stepName, String workflowId) {
        RuntimeException last = null;
        for (int i = 1; i <= PARSE_RETRY_MAX_ATTEMPTS; i++) {
            String candidate = (i == 1) ? response : extractJsonObject(response);
            try (JsonReader reader = Json.createReader(new StringReader(candidate))) {
                return reader.readObject();
            } catch (RuntimeException ex) {
                last = ex;
            }
        }
        LOGGER.log(Level.SEVERE,
                "[workflowId={0}] Step FAILED: {1}; invalid JSON response={2}",
                new Object[]{workflowId, stepName, response});
        throw new IllegalStateException("Workflow step returned invalid JSON: " + stepName + " - " + response, last);
    }

    private static boolean isTrackingOrExecutionStep(String stepName) {
        String normalized = stepName == null ? "" : stepName.toLowerCase(Locale.ROOT);
        return normalized.contains("stop servers")
                || normalized.contains("start servers")
                || normalized.contains("apply patches")
                || normalized.contains("async tracking");
    }

    private static WorkflowOperationResponse enrichAsyncIdentifiers(WorkflowOperationResponse operation,
                                                                    String rawResponse) {
        if (operation == null || !Boolean.TRUE.equals(operation.async())) {
            return operation;
        }
        String host = operation.host();
        String pid = operation.pid();
        if (isBlank(host)) {
            host = firstNonBlank(extractHost(operation.message()), extractHost(rawResponse));
        }
        if (isBlank(pid)) {
            pid = firstNonBlank(extractPid(operation.message()), extractPid(rawResponse));
        }
        if (Objects.equals(host, operation.host()) && Objects.equals(pid, operation.pid())) {
            return operation;
        }
        return new WorkflowOperationResponse(
                operation.status(),
                operation.operation(),
                operation.domain(),
                operation.async(),
                host,
                pid,
                operation.message());
    }

    private static WorkflowOperationResponse normalizeOperationResponse(WorkflowOperationResponse operation,
                                                                        String stepName,
                                                                        String rawResponse) {
        if (operation == null) {
            return null;
        }

        String normalizedOperation = isBlank(operation.operation()) ? stepName : operation.operation();
        String normalizedMessage = isBlank(operation.message())
                ? firstNonBlank(rawResponse, operation.status())
                : operation.message();

        Boolean normalizedAsync = operation.async();
        if (normalizedAsync == null) {
            normalizedAsync = !isBlank(operation.host()) || !isBlank(operation.pid())
                    || "running".equalsIgnoreCase(operation.status())
                    || "started".equalsIgnoreCase(operation.status());
        }

        return new WorkflowOperationResponse(
                operation.status(),
                normalizedOperation,
                operation.domain(),
                normalizedAsync,
                operation.host(),
                operation.pid(),
                normalizedMessage);
    }

    private static String extractPid(String text) {
        if (isBlank(text)) {
            return null;
        }
        Matcher matcher = PID_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        Matcher jsonMatcher = JSON_PID_PATTERN.matcher(text);
        return jsonMatcher.find() ? jsonMatcher.group(1) : null;
    }

    private static String extractHost(String text) {
        if (isBlank(text)) {
            return null;
        }
        Matcher matcher = HOST_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        Matcher jsonMatcher = JSON_HOST_PATTERN.matcher(text);
        return jsonMatcher.find() ? jsonMatcher.group(1) : null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static String workflowJsonPrompt(String taskInstruction) {
        return (taskInstruction + "\n"
                + "Return strict JSON only (single top-level object, no markdown, no prose outside JSON) using this schema:\n"
                + "{\n"
                + "  \"status\": \"started|running|completed|failed\",\n"
                + "  \"operation\": \"<operation-name>\",\n"
                + "  \"domain\": \"<domain-or-empty>\",\n"
                + "  \"async\": true|false,\n"
                + "  \"host\": \"<host-or-empty>\",\n"
                + "  \"pid\": \"<pid-or-empty>\",\n"
                + "  \"message\": \"<factual-tool-summary>\"\n"
                + "}\n"
                + "Always include status, operation, async, and message."
        ).trim();
    }

    private static String getString(JsonObject object, String key) {
        if (object == null || key == null || !object.containsKey(key) || object.isNull(key)) {
            return null;
        }
        return object.getString(key, null);
    }

    private static Boolean getBoolean(JsonObject object, String key) {
        if (object == null || key == null || !object.containsKey(key) || object.isNull(key)) {
            return null;
        }
        return switch (object.get(key).getValueType()) {
            case TRUE -> Boolean.TRUE;
            case FALSE -> Boolean.FALSE;
            default -> null;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}