package com.example.wls.agentic.dto;

import io.helidon.json.binding.Json;

import java.util.List;
import java.util.Map;

@Json.Entity
public record TaskContext(
        String taskId,
        String conversationId,
        String userId,
        String intent,
        String targetDomain,
        String targetServers,
        String targetHosts,
        Map<String, String> hostPids,
        String environment,
        String riskLevel,
        Boolean approvalRequired,
        Boolean confirmTargetOnImplicitReuse,
        String constraints,
        String memorySummary,
        String pendingIntent,
        Boolean awaitingFollowUp,
        String lastUserRequest,
        String lastAssistantQuestion,
        List<String> activeWorkflowIds,
        String lastReferencedWorkflowId,
        String failureReason) {

    public static TaskContext empty() {
        return new TaskContext(
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
                null,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public TaskContext withMemorySummary(String newMemorySummary) {
        return new TaskContext(taskId, conversationId, userId, intent, targetDomain, targetServers, targetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, newMemorySummary,
                pendingIntent, awaitingFollowUp, lastUserRequest, lastAssistantQuestion, activeWorkflowIds,
                lastReferencedWorkflowId, failureReason);
    }

    public TaskContext withIntent(String newIntent) {
        return new TaskContext(taskId, conversationId, userId, newIntent, targetDomain, targetServers, targetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary,
                pendingIntent, awaitingFollowUp, lastUserRequest, lastAssistantQuestion, activeWorkflowIds,
                lastReferencedWorkflowId, failureReason);
    }

    public TaskContext withConversationId(String newConversationId) {
        return new TaskContext(taskId, newConversationId, userId, intent, targetDomain, targetServers, targetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary,
                pendingIntent, awaitingFollowUp, lastUserRequest, lastAssistantQuestion, activeWorkflowIds,
                lastReferencedWorkflowId, failureReason);
    }

    public TaskContext withTargetDomain(String newTargetDomain) {
        return new TaskContext(taskId, conversationId, userId, intent, newTargetDomain, targetServers, targetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary,
                pendingIntent, awaitingFollowUp, lastUserRequest, lastAssistantQuestion, activeWorkflowIds,
                lastReferencedWorkflowId, failureReason);
    }

    public TaskContext withTargetServers(String newTargetServers) {
        return new TaskContext(taskId, conversationId, userId, intent, targetDomain, newTargetServers, targetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary,
                pendingIntent, awaitingFollowUp, lastUserRequest, lastAssistantQuestion, activeWorkflowIds,
                lastReferencedWorkflowId, failureReason);
    }

    public TaskContext withTargetHosts(String newTargetHosts) {
        return new TaskContext(taskId, conversationId, userId, intent, targetDomain, targetServers, newTargetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary,
                pendingIntent, awaitingFollowUp, lastUserRequest, lastAssistantQuestion, activeWorkflowIds,
                lastReferencedWorkflowId, failureReason);
    }

    public TaskContext withHostPids(Map<String, String> newHostPids) {
        return new TaskContext(taskId, conversationId, userId, intent, targetDomain, targetServers, targetHosts, newHostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary,
                pendingIntent, awaitingFollowUp, lastUserRequest, lastAssistantQuestion, activeWorkflowIds,
                lastReferencedWorkflowId, failureReason);
    }

    public TaskContext withLastUserRequest(String newLastUserRequest) {
        return new TaskContext(taskId, conversationId, userId, intent, targetDomain, targetServers, targetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary,
                pendingIntent, awaitingFollowUp, newLastUserRequest, lastAssistantQuestion, activeWorkflowIds,
                lastReferencedWorkflowId, failureReason);
    }

    public TaskContext withPendingFollowUp(String newPendingIntent,
                                           Boolean newAwaitingFollowUp,
                                           String newLastAssistantQuestion) {
        return new TaskContext(taskId, conversationId, userId, intent, targetDomain, targetServers, targetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary,
                newPendingIntent, newAwaitingFollowUp, lastUserRequest, newLastAssistantQuestion, activeWorkflowIds,
                lastReferencedWorkflowId, failureReason);
    }

    public TaskContext withActiveWorkflowIds(List<String> newActiveWorkflowIds) {
        return new TaskContext(taskId, conversationId, userId, intent, targetDomain, targetServers, targetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary,
                pendingIntent, awaitingFollowUp, lastUserRequest, lastAssistantQuestion, newActiveWorkflowIds,
                lastReferencedWorkflowId, failureReason);
    }

    public TaskContext withLastReferencedWorkflowId(String newLastReferencedWorkflowId) {
        return new TaskContext(taskId, conversationId, userId, intent, targetDomain, targetServers, targetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary,
                pendingIntent, awaitingFollowUp, lastUserRequest, lastAssistantQuestion, activeWorkflowIds,
                newLastReferencedWorkflowId, failureReason);
    }

    public TaskContext withFailureReason(String newFailureReason) {
        return new TaskContext(taskId, conversationId, userId, intent, targetDomain, targetServers, targetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary,
                pendingIntent, awaitingFollowUp, lastUserRequest, lastAssistantQuestion, activeWorkflowIds,
                lastReferencedWorkflowId, newFailureReason);
    }
}