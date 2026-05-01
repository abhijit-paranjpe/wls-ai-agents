package com.oracle.wls.agentic.ai;

import com.oracle.wls.agentic.workflow.WorkflowStateMutationService;
import io.helidon.service.registry.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service.Singleton
public class WorkflowExecutionSequenceAgent {

    private static final Logger LOGGER = Logger.getLogger(WorkflowExecutionSequenceAgent.class.getName());
    private static final Pattern JSON_STATUS_PATTERN = Pattern.compile("\\\"status\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_MESSAGE_PATTERN = Pattern.compile("\\\"message\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"", Pattern.CASE_INSENSITIVE);
    private static final int MAX_MONITOR_POLL_ATTEMPTS = 120;
    private static final long MONITOR_POLL_SLEEP_MILLIS = 120_000L;

    private final DomainRuntimeAgent domainRuntimeAgent;
    private final MonitoringAgent monitoringAgent;
    private final PatchingAgent patchingAgent;
    private final WorkflowStateMutationService workflowStateMutationService;

    @Service.Inject
    public WorkflowExecutionSequenceAgent(DomainRuntimeAgent domainRuntimeAgent,
                                          MonitoringAgent monitoringAgent,
                                          PatchingAgent patchingAgent,
                                          WorkflowStateMutationService workflowStateMutationService) {
        this.domainRuntimeAgent = Objects.requireNonNull(domainRuntimeAgent, "domainRuntimeAgent must not be null");
        this.monitoringAgent = Objects.requireNonNull(monitoringAgent, "monitoringAgent must not be null");
        this.patchingAgent = Objects.requireNonNull(patchingAgent, "patchingAgent must not be null");
        this.workflowStateMutationService = Objects.requireNonNull(workflowStateMutationService,
                "workflowStateMutationService must not be null");
    }

    public String run(String workflowId,
                      String targetDomain,
                      String instruction,
                      String question,
                      WorkflowExecutionPlan executionPlan) {
        LOGGER.info(() -> "[workflow=" + workflowId + "] Starting deterministic execution sequence for domain=" + targetDomain);
        LOGGER.fine(() -> "[workflow=" + workflowId + "] instruction=" + safe(instruction) + " | initialQuestion=" + safe(question));
        Objects.requireNonNull(executionPlan, "executionPlan must not be null");
        List<WorkflowExecutionStep> steps = executionPlan.steps();
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("executionPlan.steps must not be empty");
        }

        String lastResponse = null;

        for (WorkflowExecutionStep step : steps) {
            AgentCaller caller = resolveCaller(step.agentType());
            String questionTemplate = step.stepQuestionTemplate().formatted(targetDomain);
            lastResponse = executeStep(workflowId,
                    targetDomain,
                    instruction,
                    step.stepNumber(),
                    step.stepName(),
                    questionTemplate,
                    caller,
                    lastResponse,
                    step.asyncOriginStepToCompleteOnSuccess());
            if (isFailure(lastResponse)) {
                return lastResponse;
            }
        }

        String finalResponse = lastResponse;
        LOGGER.info(() -> "[workflow=" + workflowId + "] Deterministic execution sequence completed. Final response=" + compact(finalResponse));
        return lastResponse;
    }

    private String executeStep(String workflowId,
                               String targetDomain,
                               String instruction,
                               int stepNumber,
                               String stepName,
                               String stepQuestion,
                               AgentCaller caller,
                               String lastResponse,
                               String asyncOriginStepToCompleteOnSuccess) {
        String composedQuestion = """
                %s

                Workflow instruction: %s
                targetDomain: %s
                lastResponse: %s
                """.formatted(stepQuestion, safe(instruction), safe(targetDomain), safe(lastResponse));

        LOGGER.info(() -> "[workflow=" + workflowId + "] Step " + stepNumber + " (" + stepName + ") START");
        LOGGER.fine(() -> "[workflow=" + workflowId + "] Step " + stepNumber + " question=" + composedQuestion);
        workflowStateMutationService.markStepInExecution(workflowId, stepName, "Step started");

        String response = caller.call(composedQuestion);

        if (isMonitorStep(stepName)) {
            response = pollMonitorUntilTerminal(workflowId,
                    targetDomain,
                    instruction,
                    stepNumber,
                    stepName,
                    stepQuestion,
                    caller,
                    response);
        }

        final String finalResponse = response;
        LOGGER.info(() -> "[workflow=" + workflowId + "] Step " + stepNumber + " (" + stepName + ") END response=" + compact(finalResponse));
        if (isFailure(response)) {
            workflowStateMutationService.markStepFailedAndFailWorkflow(workflowId, stepName, extractMessage(response));
            LOGGER.warning(() -> "[workflow=" + workflowId + "] Step " + stepNumber + " (" + stepName + ") FAILED. Halting sequence.");
        } else {
            if (shouldMarkStepCompleted(stepName, response, asyncOriginStepToCompleteOnSuccess)) {
                workflowStateMutationService.markStepCompleted(workflowId, stepName, extractMessage(response));
                completeRelatedAsyncOriginStep(workflowId, asyncOriginStepToCompleteOnSuccess, response);
            } else {
                LOGGER.info(() -> "[workflow=" + workflowId + "] Step " + stepNumber + " (" + stepName
                        + ") remains IN_EXECUTION while async work is in progress.");
            }
        }
        return response;
    }

    private String pollMonitorUntilTerminal(String workflowId,
                                            String targetDomain,
                                            String instruction,
                                            int stepNumber,
                                            String stepName,
                                            String stepQuestion,
                                            AgentCaller caller,
                                            String initialResponse) {
        String response = initialResponse;
        int attempts = 1;
        while (isRunning(response)) {
            if (attempts >= MAX_MONITOR_POLL_ATTEMPTS) {
                String timeoutResponse = """
                        {"status":"failed","operation":"track-async-job","domain":"%s","message":"Monitoring exceeded max attempts (%d) for step %s"}
                        """.formatted(safe(targetDomain), MAX_MONITOR_POLL_ATTEMPTS, stepName);
                LOGGER.warning(() -> "[workflow=" + workflowId + "] Step " + stepNumber + " (" + stepName
                        + ") exceeded monitor poll attempts. Marking as failed.");
                return timeoutResponse;
            }

            sleepBeforeNextMonitorPoll(workflowId, stepNumber, stepName, attempts + 1);

            String nextQuestion = """
                    %s

                    Workflow instruction: %s
                    targetDomain: %s
                    lastResponse: %s
                    """.formatted(stepQuestion, safe(instruction), safe(targetDomain), safe(response));

            response = caller.call(nextQuestion);
            attempts++;
            final String captured = response;
            final int attemptNumber = attempts;
            LOGGER.info(() -> "[workflow=" + workflowId + "] Step " + stepNumber + " (" + stepName
                    + ") monitor poll #" + attemptNumber + " response=" + compact(captured));
        }
        return response;
    }

    private static boolean isMonitorStep(String stepName) {
        return stepName != null && stepName.startsWith("monitor-");
    }

    private static boolean isRunning(String response) {
        return "running".equalsIgnoreCase(extractStatus(response));
    }

    private static boolean isFailure(String response) {
        String status = extractStatus(response);
        if (status == null || status.isBlank()) {
            return response == null || response.isBlank();
        }
        return "failed".equalsIgnoreCase(status);
    }

    private static String extractStatus(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        Matcher matcher = JSON_STATUS_PATTERN.matcher(response);
        if (matcher.find()) {
            return matcher.group(1).trim().toLowerCase(Locale.ROOT);
        }
        String normalized = response.toLowerCase(Locale.ROOT);
        if (normalized.contains("status=failed")) {
            return "failed";
        }
        if (normalized.contains("status=running")) {
            return "running";
        }
        if (normalized.contains("status=completed")) {
            return "completed";
        }
        return null;
    }

    private static String extractMessage(String response) {
        if (response == null || response.isBlank()) {
            return "No response returned by step.";
        }
        Matcher matcher = JSON_MESSAGE_PATTERN.matcher(response);
        if (matcher.find()) {
            String message = matcher.group(1);
            if (message != null && !message.isBlank()) {
                return message.trim();
            }
        }
        return compact(response);
    }

    private static boolean shouldMarkStepCompleted(String stepName,
                                                   String response,
                                                   String asyncOriginStepToCompleteOnSuccess) {
        if (stepName == null || asyncOriginStepToCompleteOnSuccess == null) {
            return true;
        }
        return !isAsyncPatchApplyInProgress(response);
    }

    private void completeRelatedAsyncOriginStep(String workflowId,
                                                String asyncOriginStepToCompleteOnSuccess,
                                                String response) {
        if (asyncOriginStepToCompleteOnSuccess != null && !asyncOriginStepToCompleteOnSuccess.isBlank()) {
            workflowStateMutationService.markStepCompleted(workflowId,
                    asyncOriginStepToCompleteOnSuccess,
                    extractMessage(response));
        }
    }

    private AgentCaller resolveCaller(WorkflowStepAgentType type) {
        return switch (type) {
            case DOMAIN_RUNTIME -> domainRuntimeAgent::analyzeRequest;
            case MONITORING -> monitoringAgent::analyzeRequest;
            case PATCHING -> patchingAgent::analyzeRequest;
        };
    }

    private static boolean isAsyncPatchApplyInProgress(String response) {
        if (response == null || response.isBlank()) {
            return false;
        }
        String normalized = response.toLowerCase(Locale.ROOT);

        boolean hasTrackingIdentifiers = normalized.contains("\"tracking\"") || normalized.contains("\"hostpids\"");
        boolean indicatesInProgress = normalized.contains("\"status\":\"running\"")
                || normalized.contains("started")
                || normalized.contains("initiated")
                || normalized.contains("in progress")
                || normalized.contains("still running");

        return hasTrackingIdentifiers && indicatesInProgress;
    }

    private static void sleepBeforeNextMonitorPoll(String workflowId, int stepNumber, String stepName, int attempt) {
        try {
            Thread.sleep(MONITOR_POLL_SLEEP_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning(() -> "[workflow=" + workflowId + "] Step " + stepNumber + " (" + stepName
                    + ") interrupted while waiting for monitor poll #" + attempt + ".");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String compact(String response) {
        if (response == null) {
            return "<null>";
        }
        String flattened = response.replaceAll("\\s+", " ").trim();
        return flattened.length() > 320 ? flattened.substring(0, 320) + "..." : flattened;
    }

    @FunctionalInterface
    private interface AgentCaller {
        String call(String question);
    }
}