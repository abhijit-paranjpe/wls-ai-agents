package com.example.wls.agentic.workflow;

import java.time.Instant;
import java.util.UUID;

public record WorkflowState(
        String workflowId,
        String conversationId,
        String userId,
        String workflowType,
        String workflowStep,
        String workflowStatus,
        String targetDomain,
        String targetServers,
        String targetHosts,
        String requestedOperation,
        String pendingUserAction,
        String lastUserRequest,
        String lastAssistantQuestion,
        String lastError,
        boolean terminal,
        String createdAt,
        String updatedAt) {

    public static WorkflowState create(String conversationId,
                                       String userId,
                                       String workflowType,
                                       String workflowStep,
                                       String workflowStatus,
                                       String targetDomain,
                                       String targetServers,
                                       String targetHosts,
                                       String requestedOperation,
                                       String pendingUserAction,
                                       String lastUserRequest,
                                       String lastAssistantQuestion,
                                       String lastError,
                                       boolean terminal) {
        String now = Instant.now().toString();
        return new WorkflowState(
                "wf-" + UUID.randomUUID(),
                conversationId,
                userId,
                workflowType,
                workflowStep,
                workflowStatus,
                targetDomain,
                targetServers,
                targetHosts,
                requestedOperation,
                pendingUserAction,
                lastUserRequest,
                lastAssistantQuestion,
                lastError,
                terminal,
                now,
                now);
    }

    public WorkflowState touch(String workflowStep,
                               String workflowStatus,
                               String targetDomain,
                               String targetServers,
                               String targetHosts,
                               String requestedOperation,
                               String pendingUserAction,
                               String lastUserRequest,
                               String lastAssistantQuestion,
                               String lastError,
                               boolean terminal) {
        return new WorkflowState(
                workflowId,
                conversationId,
                userId,
                workflowType,
                workflowStep,
                workflowStatus,
                targetDomain,
                targetServers,
                targetHosts,
                requestedOperation,
                pendingUserAction,
                lastUserRequest,
                lastAssistantQuestion,
                lastError,
                terminal,
                createdAt,
                Instant.now().toString());
    }

    public boolean hasWorkflow() {
        return workflowType != null && !workflowType.isBlank();
    }
}