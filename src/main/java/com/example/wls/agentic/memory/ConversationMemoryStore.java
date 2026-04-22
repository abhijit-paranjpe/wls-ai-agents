package com.example.wls.agentic.memory;

import com.example.wls.agentic.dto.TaskContext;
import com.example.wls.agentic.dto.WorkflowHistoryRecord;

import java.util.Optional;

public interface ConversationMemoryStore {

    Optional<String> loadSummary(String conversationId);

    Optional<TaskContext> loadTaskContext(String conversationId);

    void saveSummary(String conversationId, String summary);

    void saveTaskContext(String conversationId, TaskContext taskContext);

    Optional<WorkflowHistoryRecord> loadWorkflowHistory(String domain, String operationType);

    Optional<WorkflowHistoryRecord> loadLatestWorkflowHistory(String domain);

    void saveWorkflowHistory(WorkflowHistoryRecord workflowHistoryRecord);
}
