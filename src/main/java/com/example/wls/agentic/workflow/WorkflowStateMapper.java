package com.example.wls.agentic.workflow;

import com.example.wls.agentic.ai.RequestIntent;
import com.example.wls.agentic.dto.TaskContext;

public final class WorkflowStateMapper {

    private WorkflowStateMapper() {
    }

    public static WorkflowState fromTaskContext(TaskContext context) {
        if (context == null || isBlank(context.conversationId()) || isBlank(context.workflowType())) {
            return null;
        }

        boolean terminal = isTerminal(context.workflowStep(), context.workflowStatus());
        return WorkflowState.create(
                context.conversationId(),
                context.userId(),
                context.workflowType(),
                context.workflowStep(),
                context.workflowStatus(),
                context.targetDomain(),
                context.targetServers(),
                context.targetHosts(),
                null,
                Boolean.TRUE.equals(context.awaitingFollowUp()) ? context.pendingIntent() : null,
                context.lastUserRequest(),
                context.lastAssistantQuestion(),
                null,
                terminal);
    }

    public static TaskContext applyToTaskContext(WorkflowState state, TaskContext base) {
        TaskContext safeBase = base == null ? TaskContext.empty() : base;
        if (state == null || !state.hasWorkflow()) {
            return safeBase;
        }

        Boolean awaitingFollowUp = safeBase.awaitingFollowUp() != null
                ? safeBase.awaitingFollowUp()
                : state.pendingUserAction() != null;

        return new TaskContext(
                safeBase.taskId(),
                firstNonBlank(safeBase.conversationId(), state.conversationId()),
                firstNonBlank(safeBase.userId(), state.userId()),
                firstNonBlank(safeBase.intent(), RequestIntent.WORKFLOW_REQUEST.name()),
                firstNonBlank(safeBase.targetDomain(), state.targetDomain()),
                firstNonBlank(safeBase.targetServers(), state.targetServers()),
                firstNonBlank(safeBase.targetHosts(), state.targetHosts()),
                safeBase.hostPids(),
                safeBase.environment(),
                safeBase.riskLevel(),
                safeBase.approvalRequired(),
                safeBase.confirmTargetOnImplicitReuse(),
                safeBase.constraints(),
                safeBase.memorySummary(),
                firstNonBlank(safeBase.pendingIntent(), state.pendingUserAction()),
                awaitingFollowUp,
                firstNonBlank(safeBase.lastUserRequest(), state.lastUserRequest()),
                firstNonBlank(safeBase.lastAssistantQuestion(), state.lastAssistantQuestion()),
                firstNonBlank(safeBase.workflowType(), state.workflowType()),
                firstNonBlank(safeBase.workflowStep(), state.workflowStep()),
                firstNonBlank(safeBase.workflowStatus(), state.workflowStatus()),
                safeBase.failureReason());
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return !isBlank(preferred) ? preferred : fallback;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isTerminal(String workflowStep, String workflowStatus) {
        String step = normalizeToken(workflowStep);
        String status = normalizeToken(workflowStatus);
        return "COMPLETED".equals(status)
                || "SUCCEEDED".equals(status)
                || "FAILED".equals(status)
                || "ABORTED".equals(status)
                || "CANCELLED".equals(status)
                || "COMPLETED".equals(step)
                || "DONE".equals(step)
                || "FINISHED".equals(step)
                || "FAILED".equals(step);
    }

    private static String normalizeToken(String value) {
        if (isBlank(value)) {
            return null;
        }
        return value.trim().replace('-', '_').replace(' ', '_').toUpperCase();
    }
}