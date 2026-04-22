package com.example.wls.agentic.memory;

import com.example.wls.agentic.dto.TaskContext;
import com.example.wls.agentic.dto.WorkflowHistoryRecord;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryConversationMemoryStore implements ConversationMemoryStore {

    private final ConcurrentMap<String, String> summariesByConversationId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TaskContext> taskContextsByConversationId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WorkflowHistoryRecord> workflowHistoryByKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WorkflowHistoryRecord> latestWorkflowHistoryByDomain = new ConcurrentHashMap<>();

    @Override
    public Optional<String> loadSummary(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(summariesByConversationId.get(conversationId));
    }

    @Override
    public Optional<TaskContext> loadTaskContext(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(taskContextsByConversationId.get(conversationId));
    }

    @Override
    public void saveSummary(String conversationId, String summary) {
        if (conversationId == null || conversationId.isBlank() || summary == null) {
            return;
        }
        summariesByConversationId.put(conversationId, summary);
    }

    @Override
    public void saveTaskContext(String conversationId, TaskContext taskContext) {
        if (conversationId == null || conversationId.isBlank() || taskContext == null) {
            return;
        }
        taskContextsByConversationId.put(conversationId, taskContext);
    }

    @Override
    public Optional<WorkflowHistoryRecord> loadWorkflowHistory(String domain, String operationType) {
        String key = workflowHistoryKey(domain, operationType);
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(workflowHistoryByKey.get(key));
    }

    @Override
    public Optional<WorkflowHistoryRecord> loadLatestWorkflowHistory(String domain) {
        String normalizedDomain = normalizeDomain(domain);
        if (normalizedDomain == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(latestWorkflowHistoryByDomain.get(normalizedDomain));
    }

    @Override
    public void saveWorkflowHistory(WorkflowHistoryRecord workflowHistoryRecord) {
        if (workflowHistoryRecord == null) {
            return;
        }
        String key = workflowHistoryKey(workflowHistoryRecord.domain(), workflowHistoryRecord.operationType());
        String normalizedDomain = normalizeDomain(workflowHistoryRecord.domain());
        if (key == null || normalizedDomain == null) {
            return;
        }

        workflowHistoryByKey.put(key, workflowHistoryRecord);
        latestWorkflowHistoryByDomain.merge(
                normalizedDomain,
                workflowHistoryRecord,
                (existing, candidate) -> isNewer(candidate, existing) ? candidate : existing);
    }

    private static boolean isNewer(WorkflowHistoryRecord candidate, WorkflowHistoryRecord existing) {
        if (candidate == null) {
            return false;
        }
        if (existing == null) {
            return true;
        }
        String candidateUpdatedAt = candidate.updatedAt();
        String existingUpdatedAt = existing.updatedAt();
        if (candidateUpdatedAt == null || candidateUpdatedAt.isBlank()) {
            return false;
        }
        if (existingUpdatedAt == null || existingUpdatedAt.isBlank()) {
            return true;
        }
        return candidateUpdatedAt.compareTo(existingUpdatedAt) >= 0;
    }

    private static String workflowHistoryKey(String domain, String operationType) {
        String normalizedDomain = normalizeDomain(domain);
        String normalizedOperation = normalizeOperationType(operationType);
        if (normalizedDomain == null || normalizedOperation == null) {
            return null;
        }
        return normalizedDomain + "::" + normalizedOperation;
    }

    private static String normalizeDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return null;
        }
        return domain.trim().toLowerCase();
    }

    private static String normalizeOperationType(String operationType) {
        if (operationType == null || operationType.isBlank()) {
            return null;
        }
        return operationType.trim().toUpperCase();
    }
}
