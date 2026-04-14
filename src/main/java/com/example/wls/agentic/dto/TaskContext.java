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
        String memorySummary) {

    public static TaskContext empty() {
        return new TaskContext(null, null, null, null, null, null, null, null, null, null, null, true, null, null);
    }

    public String toPromptContext() {
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
                """.formatted(
                nz(taskId),
                nz(conversationId),
                nz(userId),
                nz(intent),
                nz(targetDomain),
                nz(targetServers),
                nz(targetHosts),
                hostPids == null ? "" : hostPids,
                nz(environment),
                nz(riskLevel),
                approvalRequired == null ? "" : approvalRequired,
                confirmTargetOnImplicitReuse == null ? "" : confirmTargetOnImplicitReuse,
                nz(constraints),
                nz(memorySummary));
    }

    public TaskContext withMemorySummary(String newMemorySummary) {
        return new TaskContext(taskId, conversationId, userId, intent, targetDomain, targetServers, targetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, newMemorySummary);
    }

    public TaskContext withTargetDomain(String newTargetDomain) {
        return new TaskContext(taskId, conversationId, userId, intent, newTargetDomain, targetServers, targetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary);
    }

    public TaskContext withTargetServers(String newTargetServers) {
        return new TaskContext(taskId, conversationId, userId, intent, targetDomain, newTargetServers, targetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary);
    }

    public TaskContext withTargetHosts(String newTargetHosts) {
        return new TaskContext(taskId, conversationId, userId, intent, targetDomain, targetServers, newTargetHosts, hostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary);
    }

    public TaskContext withHostPids(Map<String, String> newHostPids) {
        return new TaskContext(taskId, conversationId, userId, intent, targetDomain, targetServers, targetHosts, newHostPids,
                environment, riskLevel, approvalRequired, confirmTargetOnImplicitReuse, constraints, memorySummary);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}