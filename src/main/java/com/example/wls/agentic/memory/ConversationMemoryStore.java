package com.example.wls.agentic.memory;

import com.example.wls.agentic.dto.TaskContext;

import java.util.Optional;

public interface ConversationMemoryStore {

    Optional<String> loadSummary(String conversationId);

    Optional<TaskContext> loadTaskContext(String conversationId);

    void saveSummary(String conversationId, String summary);

    void saveTaskContext(String conversationId, TaskContext taskContext);
}
