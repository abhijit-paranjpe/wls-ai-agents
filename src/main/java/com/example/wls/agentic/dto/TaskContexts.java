package com.example.wls.agentic.dto;

public final class TaskContexts {

    private TaskContexts() {
    }

    public static String toPromptContext(TaskContext context) {
        TaskContext safeContext = context == null ? TaskContext.empty() : context;
        return """
                taskId: %s
                conversationId: %s
                userId: %s
                intent: %s
                targetDomain: %s
                targetServers: %s
                targetHosts: %s
                hostPids: %s
                environment: %s
                riskLevel: %s
                approvalRequired: %s
                confirmTargetOnImplicitReuse: %s
                constraints: %s
                memorySummary: %s
                pendingIntent: %s
                awaitingFollowUp: %s
                lastUserRequest: %s
                lastAssistantQuestion: %s
                activeWorkflowIds: %s
                lastReferencedWorkflowId: %s
                """.formatted(
                nz(safeContext.taskId()),
                nz(safeContext.conversationId()),
                nz(safeContext.userId()),
                nz(safeContext.intent()),
                nz(safeContext.targetDomain()),
                nz(safeContext.targetServers()),
                nz(safeContext.targetHosts()),
                safeContext.hostPids() == null ? "" : safeContext.hostPids(),
                nz(safeContext.environment()),
                nz(safeContext.riskLevel()),
                safeContext.approvalRequired() == null ? "" : safeContext.approvalRequired(),
                safeContext.confirmTargetOnImplicitReuse() == null ? "" : safeContext.confirmTargetOnImplicitReuse(),
                nz(safeContext.constraints()),
                nz(safeContext.memorySummary()),
                nz(safeContext.pendingIntent()),
                safeContext.awaitingFollowUp() == null ? "" : safeContext.awaitingFollowUp(),
                nz(safeContext.lastUserRequest()),
                nz(safeContext.lastAssistantQuestion()),
                safeContext.activeWorkflowIds() == null ? "" : safeContext.activeWorkflowIds(),
                nz(safeContext.lastReferencedWorkflowId()));
    }

    public static TaskContext clearPendingFollowUp(TaskContext context) {
        TaskContext safeContext = context == null ? TaskContext.empty() : context;
        return new TaskContext(
                safeContext.taskId(),
                safeContext.conversationId(),
                safeContext.userId(),
                safeContext.intent(),
                safeContext.targetDomain(),
                safeContext.targetServers(),
                safeContext.targetHosts(),
                safeContext.hostPids(),
                safeContext.environment(),
                safeContext.riskLevel(),
                safeContext.approvalRequired(),
                safeContext.confirmTargetOnImplicitReuse(),
                safeContext.constraints(),
                safeContext.memorySummary(),
                null,
                false,
                safeContext.lastUserRequest(),
                null,
                safeContext.activeWorkflowIds(),
                safeContext.lastReferencedWorkflowId(),
                null);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}