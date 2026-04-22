package com.example.wls.agentic.workflow;

import com.example.wls.agentic.ai.RequestIntent;
import com.example.wls.agentic.dto.AgentResponse;
import com.example.wls.agentic.dto.TaskContext;
import com.example.wls.agentic.dto.TaskContexts;
import com.example.wls.agentic.dto.WorkflowHistoryRecord;
import com.example.wls.agentic.memory.ConversationMemoryService;
import io.helidon.service.registry.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service.Singleton
public class WorkflowOrchestratorService {

    private static final int MAX_MEMORY_SUMMARY_CHARS = 3000;
    private static final int MAX_CONSTRAINTS_CHARS = 1000;
    private static final String PATCHING_WORKFLOW_TYPE = "PATCHING";
    private static final String PATCH_APPLY_OPERATION = "PATCH_APPLY";
    private static final String PATCH_ROLLBACK_OPERATION = "PATCH_ROLLBACK";
    private static final Set<String> SHORT_AFFIRMATIVE_REPLIES = Set.of(
            "yes", "y", "yep", "yeah", "sure", "ok", "okay", "confirm", "confirmed");
    private static final Pattern PID_PATTERN = Pattern.compile("(?i)\\bpid\\s*[:=]?\\s*(\\d+)\\b");
    private static final Pattern HOST_PATTERN = Pattern.compile("(?i)\\bhost\\s*[:=]?\\s*([A-Za-z0-9._-]+)\\b");

    private final ConversationMemoryService conversationMemoryService;
    private final PatchExecutionService patchExecutionService;
    private final InMemoryWorkflowStateStore workflowStateStore;

    @Service.Inject
    public WorkflowOrchestratorService(ConversationMemoryService conversationMemoryService,
                                       PatchExecutionService patchExecutionService,
                                       InMemoryWorkflowStateStore workflowStateStore) {
        this.conversationMemoryService = conversationMemoryService;
        this.patchExecutionService = patchExecutionService;
        this.workflowStateStore = workflowStateStore;
    }

    public AgentResponse handleWorkflowTurn(String question,
                                            TaskContext context,
                                            String summary) {
        TaskContext effectiveContext = mergeWorkflowState(context);

        AgentResponse workflowStateStatusResponse = maybeRespondFromWorkflowStateStatus(question, effectiveContext, summary);
        if (workflowStateStatusResponse != null) {
            persistWorkflowState(workflowStateStatusResponse.taskContext());
            return workflowStateStatusResponse;
        }

        AgentResponse workflowHistoryResponse = maybeRespondFromWorkflowHistory(question, effectiveContext, summary);
        if (workflowHistoryResponse != null) {
            persistWorkflowState(workflowHistoryResponse.taskContext());
            return workflowHistoryResponse;
        }

        AgentResponse deterministicPatchWorkflowResponse = maybeRunDeterministicPatchWorkflow(question, effectiveContext, summary);
        if (deterministicPatchWorkflowResponse != null) {
            persistWorkflowState(deterministicPatchWorkflowResponse.taskContext());
            return deterministicPatchWorkflowResponse;
        }

        persistWorkflowState(effectiveContext);
        return null;
    }

    private AgentResponse maybeRespondFromWorkflowStateStatus(String question,
                                                              TaskContext context,
                                                              String summary) {
        if (!looksLikeWorkflowStatusQuestion(question) || context == null || isBlank(context.conversationId())) {
            return null;
        }

        String operationType = detectRequestedWorkflowOperationType(question);
        java.util.Optional<WorkflowState> currentState = loadBestMatchingWorkflowState(
                context.conversationId(),
                context.targetDomain(),
                operationType);
        if (currentState.isEmpty() || !currentState.get().hasWorkflow()) {
            return null;
        }

        TaskContext currentContext = WorkflowStateMapper.applyToTaskContext(currentState.get(), context);
        String progress = buildPatchingWorkflowProgressSection(currentContext);
        if (isBlank(progress)) {
            return null;
        }

        String operationLabel = PATCH_ROLLBACK_OPERATION.equalsIgnoreCase(currentState.get().requestedOperation())
                ? "rollback patch"
                : "patch application";
        StringBuilder builder = new StringBuilder(progress)
                .append("\n\nCurrent ")
                .append(operationLabel)
                .append(" state from workflow tracker");
        if (!isBlank(currentState.get().updatedAt())) {
            builder.append(" (updated: ").append(currentState.get().updatedAt()).append(")");
        }
        builder.append('.');

        TaskContext responseContext = clearWorkflowAndFollowUp(context.withTargetDomain(currentContext.targetDomain()));
        return new AgentResponse(builder.toString(), summary, responseContext);
    }

    public TaskContext applyStoredWorkflowState(TaskContext context) {
        return mergeWorkflowState(context);
    }

    public void syncWorkflowState(TaskContext context) {
        persistWorkflowState(context);
    }

    private TaskContext mergeWorkflowState(TaskContext context) {
        TaskContext safeContext = context == null ? TaskContext.empty() : context;
        if (isBlank(safeContext.conversationId())) {
            return safeContext;
        }

        String requestedOperation = detectRequestedWorkflowOperationType(safeContext.lastUserRequest());
        return loadBestMatchingWorkflowState(safeContext.conversationId(), safeContext.targetDomain(), requestedOperation)
                .map(state -> WorkflowStateMapper.applyToTaskContext(state, safeContext))
                .orElse(safeContext);
    }

    private void persistWorkflowState(TaskContext context) {
        if (context == null || isBlank(context.conversationId())) {
            return;
        }

        WorkflowState state = WorkflowStateMapper.fromTaskContext(context);
        if (state == null) {
            String requestedOperation = detectRequestedWorkflowOperationType(context.lastUserRequest());
            if (!isBlank(context.targetDomain()) || !isBlank(requestedOperation)) {
                workflowStateStore.clear(context.conversationId(), context.targetDomain(), requestedOperation);
            } else {
                workflowStateStore.clear(context.conversationId());
            }
            return;
        }

        String pendingUserAction = Boolean.TRUE.equals(context.awaitingFollowUp())
                ? firstNonBlank(context.pendingIntent(), context.workflowType())
                : null;
        boolean terminal = isWorkflowTerminal(context.workflowStep(), context.workflowStatus());
        String requestedOperation = detectRequestedWorkflowOperationType(context.lastUserRequest());

        WorkflowState existing = loadBestMatchingWorkflowState(
                context.conversationId(),
                context.targetDomain(),
                requestedOperation).orElse(null);
        WorkflowState persisted = existing == null
                ? WorkflowState.create(
                context.conversationId(),
                context.userId(),
                context.workflowType(),
                context.workflowStep(),
                context.workflowStatus(),
                context.targetDomain(),
                context.targetServers(),
                context.targetHosts(),
                requestedOperation,
                pendingUserAction,
                context.lastUserRequest(),
                context.lastAssistantQuestion(),
                null,
                terminal)
                : existing.touch(
                context.workflowStep(),
                context.workflowStatus(),
                context.targetDomain(),
                context.targetServers(),
                context.targetHosts(),
                firstNonBlank(existing.requestedOperation(), requestedOperation),
                pendingUserAction,
                context.lastUserRequest(),
                context.lastAssistantQuestion(),
                null,
                terminal);
        workflowStateStore.save(persisted);
    }

    private java.util.Optional<WorkflowState> loadBestMatchingWorkflowState(String conversationId,
                                                                            String targetDomain,
                                                                            String requestedOperation) {
        if (isBlank(conversationId)) {
            return java.util.Optional.empty();
        }
        if (!isBlank(targetDomain) && !isBlank(requestedOperation)) {
            java.util.Optional<WorkflowState> exact = workflowStateStore
                    .loadByConversationIdAndDomainAndOperation(conversationId, targetDomain, requestedOperation);
            if (exact.isPresent()) {
                return exact;
            }
        }
        if (!isBlank(targetDomain)) {
            java.util.Optional<WorkflowState> byDomain = workflowStateStore
                    .loadByConversationIdAndDomain(conversationId, targetDomain);
            if (byDomain.isPresent()) {
                return byDomain;
            }
        }
        return workflowStateStore.loadLatestByConversationId(conversationId);
    }

    private AgentResponse maybeRunDeterministicPatchWorkflow(String question,
                                                             TaskContext context,
                                                             String summary) {
        if (!shouldRunDeterministicPatchWorkflow(question, context)) {
            return null;
        }

        String domain = context == null ? null : context.targetDomain();
        if (isBlank(domain)) {
            return null;
        }

        String operationType = firstNonBlank(
                detectRequestedWorkflowOperationType(context.lastUserRequest()),
                detectRequestedWorkflowOperationType(question),
                PATCH_APPLY_OPERATION);
        boolean rollbackOperation = PATCH_ROLLBACK_OPERATION.equalsIgnoreCase(operationType);

        PatchExecutionResult executionResult = rollbackOperation
                ? patchExecutionService.executeRollbackPatchFlow(domain)
                : patchExecutionService.executeRecommendedPatchFlow(domain);
        StringBuilder details = new StringBuilder();

        String stopResult = executionResult.stopResult();
        appendWorkflowStepResult(details, "Stop servers", stopResult);
        boolean stopFailed = looksLikeWorkflowStepFailure(stopResult);
        boolean stopInProgress = looksLikeInProgressResult(stopResult);
        recordWorkflowHistory(domain, PATCHING_WORKFLOW_TYPE, operationType,
                "STOPPING_SERVERS",
                stopFailed ? "FAILED" : "IN_PROGRESS",
                question,
                stopResult,
                stopFailed);
        if (stopFailed) {
            return createDeterministicPatchWorkflowResponse(
                    context,
                    summary,
                    domain,
                    "STOPPING_SERVERS",
                    "FAILED",
                    details.toString());
        }

        pauseOneMinute();

        String applyResult = executionResult.applyResult();
        appendWorkflowStepResult(details, rollbackOperation ? "Rollback patches" : "Apply patches", applyResult);
        boolean applyFailed = looksLikeWorkflowStepFailure(applyResult);
        boolean applyInProgress = looksLikeInProgressResult(applyResult);
        recordWorkflowHistory(domain, PATCHING_WORKFLOW_TYPE, operationType,
                rollbackOperation ? "ROLLING_BACK_PATCHES" : "APPLYING_PATCHES",
                applyFailed ? "FAILED" : "IN_PROGRESS",
                question,
                applyResult,
                applyFailed);

        pauseOneMinute();

        String startResult = executionResult.startResult();
        appendWorkflowStepResult(details, "Start servers", startResult);
        boolean startFailed = looksLikeWorkflowStepFailure(startResult);
        boolean startInProgress = looksLikeInProgressResult(startResult);
        recordWorkflowHistory(domain, PATCHING_WORKFLOW_TYPE, operationType,
                "STARTING_SERVERS",
                startFailed ? "FAILED" : "IN_PROGRESS",
                question,
                startResult,
                startFailed);

        pauseOneMinute();

        String verifyResult = executionResult.verifyResult();
        appendWorkflowStepResult(details, "Verify patch status", verifyResult);
        boolean verifyFailed = looksLikeWorkflowStepFailure(verifyResult);
        boolean verifyInProgress = looksLikeInProgressResult(verifyResult);

        String finalStep;
        String finalStatus;
        if (applyFailed) {
            finalStep = rollbackOperation ? "ROLLING_BACK_PATCHES" : "APPLYING_PATCHES";
            finalStatus = "FAILED";
        } else if (startFailed) {
            finalStep = "STARTING_SERVERS";
            finalStatus = "FAILED";
        } else if (verifyFailed) {
            finalStep = "VERIFYING_STATUS";
            finalStatus = "FAILED";
        } else if (stopInProgress) {
            finalStep = "STOPPING_SERVERS";
            finalStatus = "IN_PROGRESS";
        } else if (applyInProgress) {
            finalStep = rollbackOperation ? "ROLLING_BACK_PATCHES" : "APPLYING_PATCHES";
            finalStatus = "IN_PROGRESS";
        } else if (startInProgress) {
            finalStep = "STARTING_SERVERS";
            finalStatus = "IN_PROGRESS";
        } else if (verifyInProgress) {
            finalStep = "VERIFYING_STATUS";
            finalStatus = "IN_PROGRESS";
        } else {
            finalStep = "COMPLETED";
            finalStatus = "COMPLETED";
        }

        boolean terminal = isWorkflowTerminal(finalStep, finalStatus);

        recordWorkflowHistory(domain, PATCHING_WORKFLOW_TYPE, operationType,
                finalStep,
                finalStatus,
                question,
                details.toString(),
                terminal);

        return createDeterministicPatchWorkflowResponse(
                context,
                summary,
                domain,
                finalStep,
                finalStatus,
                details.toString());
    }

    private static boolean shouldRunDeterministicPatchWorkflow(String question, TaskContext context) {
        if (!isPatchingWorkflow(context)) {
            return false;
        }

        String normalizedQuestion = normalizeReply(question);
        if (Boolean.TRUE.equals(context.awaitingFollowUp()) && isPendingPatchingWorkflowFollowUp(context.pendingIntent(), context)) {
            return SHORT_AFFIRMATIVE_REPLIES.contains(normalizedQuestion);
        }

        String workflowStep = normalizeWorkflowToken(context.workflowStep());
        return !Boolean.TRUE.equals(context.awaitingFollowUp()) && isActivePatchingStage(workflowStep);
    }

    private AgentResponse createDeterministicPatchWorkflowResponse(TaskContext context,
                                                                   String summary,
                                                                   String domain,
                                                                   String workflowStep,
                                                                   String workflowStatus,
                                                                   String details) {
        TaskContext displayContext = (context == null ? TaskContext.empty() : context)
                .withTargetDomain(domain)
                .withWorkflow(PATCHING_WORKFLOW_TYPE, workflowStep, workflowStatus);
        String failureReason = null;
        if ("FAILED".equals(workflowStatus)) {
            FailureSignal failureSignal = extractFailureSignal(details);
            StringBuilder reasonBuilder = new StringBuilder("Workflow failed at step: ")
                    .append(workflowStep)
                    .append('.');
            if (!failureSignal.hosts().isEmpty()) {
                reasonBuilder.append(" Affected host(s): ")
                        .append(String.join(", ", failureSignal.hosts()))
                        .append('.');
            }
            if (!failureSignal.pids().isEmpty()) {
                reasonBuilder.append(" Reported PID(s): ")
                        .append(String.join(", ", failureSignal.pids()))
                        .append('.');
            }
            reasonBuilder.append(" Details: ").append(details);
            failureReason = reasonBuilder.toString();
        }
        if (failureReason != null) {
            displayContext = displayContext.withFailureReason(failureReason);
        }
        String progress = buildPatchingWorkflowProgressSection(displayContext);
        String message = isBlank(progress) ? details.trim() : progress + "\n\n" + details.trim();
        if (failureReason != null) {
            FailureSignal failureSignal = extractFailureSignal(details);
            String failureSummary = buildFailureSummary(failureSignal);
            if (!isBlank(failureSummary)) {
                message = message + "\n\n" + failureSummary;
            }
        }
        TaskContext responseContext = clearWorkflowAndFollowUp((context == null ? TaskContext.empty() : context).withTargetDomain(domain));
        if (failureReason != null) {
            responseContext = responseContext.withFailureReason(failureReason);
        }
        return new AgentResponse(message, summary, responseContext);
    }

    private void recordWorkflowHistory(String domain,
                                       String workflowType,
                                       String operationType,
                                       String workflowStep,
                                       String workflowStatus,
                                       String question,
                                       String assistantMessage,
                                       boolean terminal) {
        if (isBlank(domain) || isBlank(operationType)) {
            return;
        }
        conversationMemoryService.store().saveWorkflowHistory(new WorkflowHistoryRecord(
                domain,
                workflowType,
                operationType,
                workflowStep,
                workflowStatus,
                truncate(question, MAX_CONSTRAINTS_CHARS),
                truncate(assistantMessage, MAX_MEMORY_SUMMARY_CHARS),
                Instant.now().toString(),
                terminal));
    }

    private static void appendWorkflowStepResult(StringBuilder details, String label, String result) {
        if (details == null) {
            return;
        }
        if (!details.isEmpty()) {
            details.append("\n\n");
        }
        details.append("### ").append(label).append('\n')
                .append(isBlank(result) ? "No result returned." : result.trim());
    }

    private static boolean looksLikeWorkflowStepFailure(String result) {
        if (result == null || result.isBlank()) {
            return true;
        }

        String lower = result.toLowerCase();
        if (lower.contains("no applicable patches pending") || lower.contains("no patches pending")) {
            return false;
        }
        if (lower.contains("domain on latest patches: yes")
                || lower.contains("workflow progress")
                || lower.contains("status: in progress")
                || lower.contains("still in progress")) {
            return false;
        }

        return lower.contains(" failed")
                || lower.startsWith("failed")
                || lower.contains(" error")
                || lower.startsWith("error")
                || lower.contains("unable to")
                || lower.contains("could not")
                || lower.contains("cannot ")
                || lower.contains("exception")
                || lower.contains("unsuccessful")
                || lower.contains("timed out");
    }

    private static boolean looksLikeInProgressResult(String result) {
        if (result == null || result.isBlank()) {
            return false;
        }
        String lower = result.toLowerCase();
        return lower.contains("status: in progress")
                || lower.contains("still in progress")
                || lower.contains("workflow in progress")
                || lower.contains("operation in progress")
                || lower.contains("pidstate: running")
                || lower.contains("pidstate = running")
                || lower.contains("\"pidstate\":\"running\"")
                || lower.contains("\"pidstate\": \"running\"");
    }

    private static String buildFailureSummary(FailureSignal failureSignal) {
        if (failureSignal == null || (failureSignal.hosts().isEmpty() && failureSignal.pids().isEmpty())) {
            return null;
        }

        StringBuilder summary = new StringBuilder("Failure diagnostics:");
        if (!failureSignal.hosts().isEmpty()) {
            summary.append("\n- Host(s): ").append(String.join(", ", failureSignal.hosts()));
        }
        if (!failureSignal.pids().isEmpty()) {
            summary.append("\n- PID(s): ").append(String.join(", ", failureSignal.pids()));
        }
        return summary.toString();
    }

    private static FailureSignal extractFailureSignal(String text) {
        if (isBlank(text)) {
            return new FailureSignal(Set.of(), Set.of());
        }

        Set<String> hosts = new LinkedHashSet<>();
        Matcher hostMatcher = HOST_PATTERN.matcher(text);
        while (hostMatcher.find()) {
            String host = hostMatcher.group(1);
            if (!isBlank(host)) {
                hosts.add(host.trim());
            }
        }

        Set<String> pids = new LinkedHashSet<>();
        Matcher pidMatcher = PID_PATTERN.matcher(text);
        while (pidMatcher.find()) {
            String pid = pidMatcher.group(1);
            if (!isBlank(pid)) {
                pids.add(pid.trim());
            }
        }

        return new FailureSignal(hosts, pids);
    }

    private record FailureSignal(Set<String> hosts, Set<String> pids) {
    }

    private AgentResponse maybeRespondFromWorkflowHistory(String question,
                                                          TaskContext context,
                                                          String summary) {
        if (!looksLikeWorkflowStatusQuestion(question)) {
            return null;
        }

        String domain = context == null ? null : context.targetDomain();
        if (isBlank(domain)) {
            return null;
        }

        String requestedOperationType = detectRequestedWorkflowOperationType(question);
        WorkflowHistoryRecord history = requestedOperationType != null
                ? conversationMemoryService.store().loadWorkflowHistory(domain, requestedOperationType).orElse(null)
                : conversationMemoryService.store().loadLatestWorkflowHistory(domain).orElse(null);
        if (history == null) {
            return null;
        }

        TaskContext responseContext = clearWorkflowAndFollowUp(context.withTargetDomain(domain));
        String message = renderWorkflowHistoryResponse(history);
        return new AgentResponse(message, summary, responseContext);
    }

    private static String renderWorkflowHistoryResponse(WorkflowHistoryRecord history) {
        TaskContext historyContext = new TaskContext(
                null,
                null,
                null,
                RequestIntent.WORKFLOW_REQUEST.name(),
                history.domain(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                history.lastUserRequest(),
                null,
                history.workflowType(),
                history.workflowStep(),
                history.workflowStatus(),
                null);

        String progress = buildPatchingWorkflowProgressSection(historyContext);
        String operationLabel = PATCH_ROLLBACK_OPERATION.equalsIgnoreCase(history.operationType())
                ? "rollback patch"
                : "patch application";
        StringBuilder builder = new StringBuilder();
        if (!isBlank(progress)) {
            builder.append(progress).append("\n\n");
        }
        builder.append("Latest recorded ")
                .append(operationLabel)
                .append(" workflow status for domain `")
                .append(history.domain())
                .append("`.");
        if (!isBlank(history.updatedAt())) {
            builder.append("\n- Last updated: ").append(history.updatedAt());
        }
        if (!isBlank(history.lastAssistantMessage())) {
            builder.append("\n\nLast recorded update:\n")
                    .append(history.lastAssistantMessage().trim());
        }
        return builder.toString();
    }

    private static boolean looksLikeWorkflowStatusQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }

        String lower = question.toLowerCase();
        boolean mentionsStatus = lower.contains("status")
                || lower.contains("complete")
                || lower.contains("completed")
                || lower.contains("progress")
                || lower.contains("latest update");
        boolean mentionsWorkflow = lower.contains("workflow")
                || lower.contains("patch")
                || lower.contains("rollback")
                || lower.contains("roll back");
        return mentionsStatus && mentionsWorkflow;
    }

    private static String detectRequestedWorkflowOperationType(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }

        String lower = question.toLowerCase();
        if (lower.contains("rollback") || lower.contains("roll back")) {
            return PATCH_ROLLBACK_OPERATION;
        }
        if ((lower.contains("apply") || lower.contains("patching")) && lower.contains("patch")) {
            return PATCH_APPLY_OPERATION;
        }
        return null;
    }

    private static String buildPatchingWorkflowProgressSection(TaskContext context) {
        if (context == null) {
            return null;
        }

        String workflowStep = normalizeWorkflowToken(context.workflowStep());
        String workflowStatus = normalizeWorkflowToken(context.workflowStatus());
        if (isBlank(workflowStep) && isBlank(workflowStatus)) {
            return null;
        }

        int currentStageIndex = resolvePatchingStageIndex(workflowStep);
        boolean terminalSuccess = isTerminalSuccessStatus(workflowStatus) || "COMPLETED".equals(workflowStep);
        boolean terminalFailure = isTerminalFailureStatus(workflowStatus) || "FAILED".equals(workflowStep);
        boolean awaitingConfirmation = "AWAITING_USER_CONFIRMATION".equals(workflowStatus)
                || "CONFIRMATION_REQUIRED".equals(workflowStep);

        if (terminalSuccess) {
            currentStageIndex = 5;
        } else if (currentStageIndex < 0) {
            currentStageIndex = awaitingConfirmation ? 0 : 1;
        }

        String title = "### Workflow progress";
        if (!isBlank(context.targetDomain())) {
            title += " for `" + context.targetDomain() + "`";
        }

        String overallStatus = buildWorkflowOverallStatus(workflowStep, workflowStatus);
        StringBuilder builder = new StringBuilder(title);
        if (!isBlank(overallStatus)) {
            builder.append('\n').append('_').append(overallStatus).append('_');
        }

        String operationType = detectRequestedWorkflowOperationType(context.lastUserRequest());
        String patchStageLabel = PATCH_ROLLBACK_OPERATION.equalsIgnoreCase(operationType)
                ? "Rollback patches"
                : "Apply patches";

        builder.append('\n')
                .append("- ").append(resolvePatchingStageMarker(0, currentStageIndex, workflowStatus, terminalFailure)).append(" Confirm patch plan")
                .append('\n')
                .append("- ").append(resolvePatchingStageMarker(1, currentStageIndex, workflowStatus, terminalFailure)).append(" Stop servers")
                .append('\n')
                .append("- ").append(resolvePatchingStageMarker(2, currentStageIndex, workflowStatus, terminalFailure)).append(" ").append(patchStageLabel)
                .append('\n')
                .append("- ").append(resolvePatchingStageMarker(3, currentStageIndex, workflowStatus, terminalFailure)).append(" Start servers")
                .append('\n')
                .append("- ").append(resolvePatchingStageMarker(4, currentStageIndex, workflowStatus, terminalFailure)).append(" Verify patch status");

        return builder.toString();
    }

    private static String resolvePatchingStageMarker(int stageIndex,
                                                     int currentStageIndex,
                                                     String workflowStatus,
                                                     boolean terminalFailure) {
        if (isTerminalCancelledStatus(workflowStatus)) {
            return stageIndex < currentStageIndex ? "✅" : "🚫";
        }

        if (currentStageIndex >= 5 && isTerminalSuccessStatus(workflowStatus)) {
            return "✅";
        }

        if (stageIndex < currentStageIndex) {
            return "✅";
        }

        if (stageIndex > currentStageIndex) {
            return "⬜";
        }

        if (terminalFailure) {
            return "❌";
        }

        if ("AWAITING_USER_CONFIRMATION".equals(workflowStatus)) {
            return "🟡";
        }

        if ("COMPLETED".equals(workflowStatus) || "SUCCEEDED".equals(workflowStatus)) {
            return "✅";
        }

        return "⏳";
    }

    private static int resolvePatchingStageIndex(String workflowStep) {
        if (isBlank(workflowStep)) {
            return -1;
        }

        return switch (workflowStep) {
            case "CONFIRMATION_REQUIRED", "AWAITING_CONFIRMATION", "RESOLVE_TARGET", "INIT",
                    "REQUESTED", "INSPECTING_PATCHES", "PATCH_SELECTION", "CONFIRM_PATCH_PLAN" -> 0;
            case "STOPPING_SERVERS", "STOP_SERVERS", "STOPPING_DOMAIN", "STOPPING_MANAGED_SERVERS" -> 1;
            case "APPLYING_PATCHES", "APPLY_PATCHES", "PATCHING", "RUNNING_OPATCH",
                    "ROLLING_BACK_PATCHES", "ROLLBACK_PATCHES", "PATCH_ROLLBACK", "ROLLBACK" -> 2;
            case "STARTING_SERVERS", "START_SERVERS", "STARTING_DOMAIN", "STARTING_MANAGED_SERVERS" -> 3;
            case "VERIFYING_STATUS", "VERIFY_PATCH_STATUS", "VERIFYING_PATCH_STATUS", "VERIFYING", "POSTCHECK" -> 4;
            case "COMPLETED", "DONE", "FINISHED" -> 5;
            case "FAILED" -> 2;
            default -> -1;
        };
    }

    private static String buildWorkflowOverallStatus(String workflowStep, String workflowStatus) {
        if (isTerminalSuccessStatus(workflowStatus) || "COMPLETED".equals(workflowStep)) {
            return "Overall status: Completed";
        }
        if (isTerminalFailureStatus(workflowStatus) || "FAILED".equals(workflowStep)) {
            return "Overall status: Failed during " + humanizeWorkflowToken(workflowStep);
        }
        if (isTerminalCancelledStatus(workflowStatus)) {
            return "Overall status: Cancelled";
        }
        if ("AWAITING_USER_CONFIRMATION".equals(workflowStatus) || "CONFIRMATION_REQUIRED".equals(workflowStep)) {
            return "Overall status: Awaiting confirmation";
        }
        if (!isBlank(workflowStep)) {
            return "Overall status: In progress - " + humanizeWorkflowToken(workflowStep);
        }
        if (!isBlank(workflowStatus)) {
            return "Overall status: " + humanizeWorkflowToken(workflowStatus);
        }
        return null;
    }

    private static boolean isPatchingWorkflow(TaskContext context) {
        return context != null && PATCHING_WORKFLOW_TYPE.equalsIgnoreCase(safe(context.workflowType()));
    }

    private static boolean isPendingPatchingWorkflowFollowUp(String pendingIntent, TaskContext context) {
        if (PATCHING_WORKFLOW_TYPE.equalsIgnoreCase(safe(context.workflowType()))) {
            return true;
        }
        if (pendingIntent == null || pendingIntent.isBlank()) {
            return false;
        }
        return "PATCHING_WORKFLOW".equalsIgnoreCase(pendingIntent)
                || (RequestIntent.WORKFLOW_REQUEST.name().equalsIgnoreCase(pendingIntent)
                && PATCHING_WORKFLOW_TYPE.equalsIgnoreCase(safe(context.workflowType())));
    }

    private static boolean isActivePatchingStage(String workflowStep) {
        if (isBlank(workflowStep)) {
            return false;
        }

        return switch (workflowStep) {
            case "STOPPING_SERVERS", "STOP_SERVERS", "STOPPING_DOMAIN", "STOPPING_MANAGED_SERVERS",
                    "APPLYING_PATCHES", "APPLY_PATCHES", "PATCHING", "RUNNING_OPATCH",
                    "ROLLING_BACK_PATCHES", "ROLLBACK_PATCHES", "PATCH_ROLLBACK", "ROLLBACK",
                    "STARTING_SERVERS", "START_SERVERS", "STARTING_DOMAIN", "STARTING_MANAGED_SERVERS",
                    "VERIFYING_STATUS", "VERIFY_PATCH_STATUS", "VERIFYING_PATCH_STATUS", "VERIFYING", "POSTCHECK" -> true;
            default -> false;
        };
    }

    private static boolean isWorkflowTerminal(String workflowStep, String workflowStatus) {
        return isTerminalSuccessStatus(workflowStatus)
                || isTerminalFailureStatus(workflowStatus)
                || isTerminalCancelledStatus(workflowStatus)
                || "COMPLETED".equals(normalizeWorkflowToken(workflowStep))
                || "DONE".equals(normalizeWorkflowToken(workflowStep))
                || "FINISHED".equals(normalizeWorkflowToken(workflowStep))
                || "FAILED".equals(normalizeWorkflowToken(workflowStep));
    }

    private static TaskContext clearWorkflowAndFollowUp(TaskContext context) {
        if (context == null) {
            return null;
        }
        return TaskContexts.clearPendingFollowUp(context).withWorkflow(null, null, null);
    }

    private static String normalizeReply(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase().replaceAll("[.!?]+$", "");
    }

    private static String normalizeWorkflowToken(String value) {
        if (isBlank(value)) {
            return null;
        }
        return value.trim().replace('-', '_').replace(' ', '_').toUpperCase();
    }

    private static String humanizeWorkflowToken(String value) {
        if (isBlank(value)) {
            return "workflow";
        }

        String normalized = value.trim().replace('-', '_').replace(' ', '_').toLowerCase();
        String[] parts = normalized.split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.isEmpty() ? "workflow" : builder.toString();
    }

    private static boolean isTerminalSuccessStatus(String workflowStatus) {
        return "COMPLETED".equals(workflowStatus) || "SUCCEEDED".equals(workflowStatus);
    }

    private static boolean isTerminalFailureStatus(String workflowStatus) {
        return "FAILED".equals(workflowStatus) || "ABORTED".equals(workflowStatus);
    }

    private static boolean isTerminalCancelledStatus(String workflowStatus) {
        return "CANCELLED".equals(workflowStatus);
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + " …[truncated]";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private void pauseOneMinute() {
        try {
            Thread.sleep(60_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
