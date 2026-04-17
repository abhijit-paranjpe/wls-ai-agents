package com.example.wls.agentic.dto;

import io.helidon.json.binding.Json;

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
        String lastAssistantQuestion) {

    public static TaskContext empty() {
        return new TaskContext(null, null, null, null, null, null, null, null, null, null, null, true,
                null, null, null, null, null, null);
    }

    public TaskContext withMemorySummary(String newMemorySummary) {
        return new TaskContext(taskId, conversationId, userId, intent, targetDomain, targetServers, targetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, newMemorySummary,
                pendingIntent, awaitingFollowUp, lastUserRequest, lastAssistantQuestion);
    }

    public TaskContext withIntent(String newIntent) {
        return new TaskContext(taskId, conversationId, userId, newIntent, targetDomain, targetServers, targetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary,
                pendingIntent, awaitingFollowUp, lastUserRequest, lastAssistantQuestion);
    }

    public TaskContext withTargetDomain(String newTargetDomain) {
        return new TaskContext(taskId, conversationId, userId, intent, newTargetDomain, targetServers, targetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary,
                pendingIntent, awaitingFollowUp, lastUserRequest, lastAssistantQuestion);
    }

    public TaskContext withTargetServers(String newTargetServers) {
        return new TaskContext(taskId, conversationId, userId, intent, targetDomain, newTargetServers, targetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary,
                pendingIntent, awaitingFollowUp, lastUserRequest, lastAssistantQuestion);
    }

    public TaskContext withTargetHosts(String newTargetHosts) {
        return new TaskContext(taskId, conversationId, userId, intent, targetDomain, targetServers, newTargetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary,
                pendingIntent, awaitingFollowUp, lastUserRequest, lastAssistantQuestion);
    }

    public TaskContext withHostPids(Map<String, String> newHostPids) {
        return new TaskContext(taskId, conversationId, userId, intent, targetDomain, targetServers, targetHosts, newHostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary,
                pendingIntent, awaitingFollowUp, lastUserRequest, lastAssistantQuestion);
    }

    public TaskContext withLastUserRequest(String newLastUserRequest) {
        return new TaskContext(taskId, conversationId, userId, intent, targetDomain, targetServers, targetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary,
                pendingIntent, awaitingFollowUp, newLastUserRequest, lastAssistantQuestion);
    }

    public TaskContext withPendingFollowUp(String newPendingIntent,
                                           Boolean newAwaitingFollowUp,
                                           String newLastAssistantQuestion) {
        return new TaskContext(taskId, conversationId, userId, intent, targetDomain, targetServers, targetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary,
                newPendingIntent, newAwaitingFollowUp, lastUserRequest, newLastAssistantQuestion);
    }
}