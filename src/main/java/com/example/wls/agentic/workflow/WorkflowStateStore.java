package com.example.wls.agentic.workflow;

import java.util.Optional;

public interface WorkflowStateStore {

    Optional<WorkflowState> loadByConversationId(String conversationId);

    Optional<WorkflowState> loadLatestByConversationId(String conversationId);

    Optional<WorkflowState> loadByConversationIdAndDomain(String conversationId, String targetDomain);

    Optional<WorkflowState> loadByConversationIdAndDomainAndOperation(String conversationId,
                                                                      String targetDomain,
                                                                      String requestedOperation);

    void save(WorkflowState workflowState);

    void clear(String conversationId);

    void clear(String conversationId, String targetDomain, String requestedOperation);
}