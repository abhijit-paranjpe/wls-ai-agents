package com.oracle.wls.agentic.memory;

import com.oracle.wls.agentic.dto.TaskContext;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryConversationMemoryStore implements ConversationMemoryStore {

    private final ConcurrentMap<String, String> summariesByConversationId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TaskContext> taskContextsByConversationId = new ConcurrentHashMap<>();

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
}
