package com.example.wls.agentic.memory;

import com.example.wls.agentic.dto.TaskContext;
import com.example.wls.agentic.dto.WorkflowHistoryRecord;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.helidon.config.Config;
import org.bson.Document;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.descending;

public class MongoConversationMemoryStore implements ConversationMemoryStore {

    private final MongoCollection<Document> collection;

    public MongoConversationMemoryStore(Config memoryConfig) {
        Config mongo = memoryConfig.get("providers.mongo");
        String uri = mongo.get("uri").asString().orElse("mongodb://localhost:27017");
        String databaseName = mongo.get("database").asString().orElse("wls_agentic");
        String collectionName = mongo.get("collection").asString().orElse("conversation_summaries");

        MongoClient client = MongoClients.create(uri);
        MongoDatabase db = client.getDatabase(databaseName);
        this.collection = db.getCollection(collectionName);
    }

    @Override
    public Optional<String> loadSummary(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        Document doc = collection.find(eq("conversationId", conversationId)).first();
        if (doc == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(doc.getString("summary"));
    }

    @Override
    public Optional<TaskContext> loadTaskContext(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        Document doc = collection.find(eq("conversationId", conversationId)).first();
        if (doc == null) {
            return Optional.empty();
        }
        Document t = (Document) doc.get("taskContext");
        if (t == null) {
            return Optional.empty();
        }
        return Optional.of(new TaskContext(
                t.getString("taskId"),
                t.getString("conversationId"),
                t.getString("userId"),
                t.getString("intent"),
                t.getString("targetDomain"),
                t.getString("targetServers"),
                t.getString("targetHosts"),
                getStringMap(t.get("hostPids", Document.class)),
                t.getString("environment"),
                t.getString("riskLevel"),
                getBoolean(t, "approvalRequired"),
                getBoolean(t, "confirmTargetOnImplicitReuse"),
                t.getString("constraints"),
                t.getString("memorySummary"),
                t.getString("pendingIntent"),
                getBoolean(t, "awaitingFollowUp"),
                t.getString("lastUserRequest"),
                t.getString("lastAssistantQuestion"),
                t.getString("workflowType"),
                t.getString("workflowStep"),
                t.getString("workflowStatus"),
                t.getString("failureReason")));
    }

    @Override
    public void saveSummary(String conversationId, String summary) {
        if (conversationId == null || conversationId.isBlank() || summary == null) {
            return;
        }
        Document filter = new Document("conversationId", conversationId);
        Document update = new Document("$set", new Document("conversationId", conversationId)
                .append("summary", summary));
        collection.updateOne(filter, update, new com.mongodb.client.model.UpdateOptions().upsert(true));
    }

    @Override
    public void saveTaskContext(String conversationId, TaskContext taskContext) {
        if (conversationId == null || conversationId.isBlank() || taskContext == null) {
            return;
        }
        Document filter = new Document("conversationId", conversationId);
        Document contextDoc = new Document("taskId", taskContext.taskId())
                .append("conversationId", taskContext.conversationId())
                .append("userId", taskContext.userId())
                .append("intent", taskContext.intent())
                .append("targetDomain", taskContext.targetDomain())
                .append("targetServers", taskContext.targetServers())
                .append("targetHosts", taskContext.targetHosts())
                .append("environment", taskContext.environment())
                .append("riskLevel", taskContext.riskLevel())
                .append("approvalRequired", taskContext.approvalRequired())
                .append("confirmTargetOnImplicitReuse", taskContext.confirmTargetOnImplicitReuse())
                .append("constraints", taskContext.constraints())
                .append("memorySummary", taskContext.memorySummary())
                .append("pendingIntent", taskContext.pendingIntent())
                .append("awaitingFollowUp", taskContext.awaitingFollowUp())
                .append("lastUserRequest", taskContext.lastUserRequest())
                .append("lastAssistantQuestion", taskContext.lastAssistantQuestion())
                .append("workflowType", taskContext.workflowType())
                .append("workflowStep", taskContext.workflowStep())
                .append("workflowStatus", taskContext.workflowStatus());
        if (taskContext.hostPids() != null && !taskContext.hostPids().isEmpty()) {
            contextDoc.append("hostPids", new Document(taskContext.hostPids()));
        }
        Document update = new Document("$set", new Document("conversationId", conversationId)
                .append("taskContext", contextDoc));
        collection.updateOne(filter, update, new com.mongodb.client.model.UpdateOptions().upsert(true));
    }

    @Override
    public Optional<WorkflowHistoryRecord> loadWorkflowHistory(String domain, String operationType) {
        String historyKey = workflowHistoryKey(domain, operationType);
        if (historyKey == null) {
            return Optional.empty();
        }

        Document doc = collection.find(and(
                        eq("recordType", "workflowHistory"),
                        eq("historyKey", historyKey)))
                .first();
        return Optional.ofNullable(toWorkflowHistoryRecord(doc));
    }

    @Override
    public Optional<WorkflowHistoryRecord> loadLatestWorkflowHistory(String domain) {
        String normalizedDomain = normalizeDomain(domain);
        if (normalizedDomain == null) {
            return Optional.empty();
        }

        Document doc = collection.find(and(
                        eq("recordType", "workflowHistory"),
                        eq("domainKey", normalizedDomain)))
                .sort(descending("updatedAt"))
                .first();
        return Optional.ofNullable(toWorkflowHistoryRecord(doc));
    }

    @Override
    public void saveWorkflowHistory(WorkflowHistoryRecord workflowHistoryRecord) {
        if (workflowHistoryRecord == null) {
            return;
        }

        String historyKey = workflowHistoryKey(workflowHistoryRecord.domain(), workflowHistoryRecord.operationType());
        String domainKey = normalizeDomain(workflowHistoryRecord.domain());
        if (historyKey == null || domainKey == null) {
            return;
        }

        Document filter = new Document("historyKey", historyKey);
        Document historyDoc = new Document("recordType", "workflowHistory")
                .append("historyKey", historyKey)
                .append("domainKey", domainKey)
                .append("domain", workflowHistoryRecord.domain())
                .append("workflowType", workflowHistoryRecord.workflowType())
                .append("operationType", workflowHistoryRecord.operationType())
                .append("workflowStep", workflowHistoryRecord.workflowStep())
                .append("workflowStatus", workflowHistoryRecord.workflowStatus())
                .append("lastUserRequest", workflowHistoryRecord.lastUserRequest())
                .append("lastAssistantMessage", workflowHistoryRecord.lastAssistantMessage())
                .append("updatedAt", workflowHistoryRecord.updatedAt())
                .append("terminal", workflowHistoryRecord.terminal());
        Document update = new Document("$set", historyDoc);
        collection.updateOne(filter, update, new com.mongodb.client.model.UpdateOptions().upsert(true));
    }

    private static Boolean getBoolean(Document document, String key) {
        Object value = document.get(key);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static Map<String, String> getStringMap(Document document) {
        if (document == null) {
            return null;
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            result.put(entry.getKey(), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return result.isEmpty() ? null : result;
    }

    private static WorkflowHistoryRecord toWorkflowHistoryRecord(Document doc) {
        if (doc == null) {
            return null;
        }
        return new WorkflowHistoryRecord(
                doc.getString("domain"),
                doc.getString("workflowType"),
                doc.getString("operationType"),
                doc.getString("workflowStep"),
                doc.getString("workflowStatus"),
                doc.getString("lastUserRequest"),
                doc.getString("lastAssistantMessage"),
                doc.getString("updatedAt"),
                getBoolean(doc, "terminal"));
    }

    private static String workflowHistoryKey(String domain, String operationType) {
        String normalizedDomain = normalizeDomain(domain);
        String normalizedOperation = normalizeOperationType(operationType);
        if (normalizedDomain == null || normalizedOperation == null) {
            return null;
        }
        return normalizedDomain + ":" + normalizedOperation;
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
