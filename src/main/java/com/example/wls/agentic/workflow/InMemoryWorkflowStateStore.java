package com.example.wls.agentic.workflow;

import io.helidon.service.registry.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Service.Singleton
public class InMemoryWorkflowStateStore implements WorkflowStateStore {

    private final ConcurrentMap<String, WorkflowState> statesByWorkflowKey = new ConcurrentHashMap<>();

    @Override
    public Optional<WorkflowState> loadByConversationId(String conversationId) {
        return loadLatestByConversationId(conversationId);
    }

    @Override
    public Optional<WorkflowState> loadLatestByConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        return statesByWorkflowKey.values().stream()
                .filter(state -> conversationId.equals(state.conversationId()))
                .max(Comparator.comparing(WorkflowState::updatedAt, Comparator.nullsLast(String::compareTo)));
    }

    @Override
    public Optional<WorkflowState> loadByConversationIdAndDomain(String conversationId, String targetDomain) {
        if (conversationId == null || conversationId.isBlank() || targetDomain == null || targetDomain.isBlank()) {
            return Optional.empty();
        }

        return statesByWorkflowKey.values().stream()
                .filter(state -> conversationId.equals(state.conversationId()))
                .filter(state -> targetDomain.equalsIgnoreCase(state.targetDomain()))
                .max(Comparator.comparing(WorkflowState::updatedAt, Comparator.nullsLast(String::compareTo)));
    }

    @Override
    public Optional<WorkflowState> loadByConversationIdAndDomainAndOperation(String conversationId,
                                                                              String targetDomain,
                                                                              String requestedOperation) {
        if (conversationId == null || conversationId.isBlank()
                || targetDomain == null || targetDomain.isBlank()
                || requestedOperation == null || requestedOperation.isBlank()) {
            return Optional.empty();
        }

        return statesByWorkflowKey.values().stream()
                .filter(state -> conversationId.equals(state.conversationId()))
                .filter(state -> targetDomain.equalsIgnoreCase(state.targetDomain()))
                .filter(state -> requestedOperation.equalsIgnoreCase(state.requestedOperation()))
                .max(Comparator.comparing(WorkflowState::updatedAt, Comparator.nullsLast(String::compareTo)));
    }

    @Override
    public void save(WorkflowState workflowState) {
        if (workflowState == null || workflowState.conversationId() == null || workflowState.conversationId().isBlank()) {
            return;
        }
        statesByWorkflowKey.put(buildKey(workflowState.conversationId(), workflowState.targetDomain(), workflowState.requestedOperation()), workflowState);
    }

    @Override
    public void clear(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        List<String> keysToRemove = statesByWorkflowKey.entrySet().stream()
                .filter(entry -> conversationId.equals(entry.getValue().conversationId()))
                .map(java.util.Map.Entry::getKey)
                .collect(Collectors.toList());
        keysToRemove.forEach(statesByWorkflowKey::remove);
    }

    @Override
    public void clear(String conversationId, String targetDomain, String requestedOperation) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        statesByWorkflowKey.remove(buildKey(conversationId, targetDomain, requestedOperation));
    }

    private static String buildKey(String conversationId, String targetDomain, String requestedOperation) {
        return normalize(conversationId) + "::" + normalize(targetDomain) + "::" + normalize(requestedOperation);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "_";
        }
        return value.trim().toLowerCase();
    }
}