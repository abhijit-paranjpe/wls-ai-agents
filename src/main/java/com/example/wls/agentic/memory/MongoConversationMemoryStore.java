package com.example.wls.agentic.memory;

import com.example.wls.agentic.dto.TaskContext;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.helidon.config.Config;
import org.bson.Document;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.mongodb.client.model.Filters.eq;

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
                t.getString("activeWorkflowId"),
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
                .append("activeWorkflowId", taskContext.activeWorkflowId())
                .append("failureReason", taskContext.failureReason());
        if (taskContext.hostPids() != null && !taskContext.hostPids().isEmpty()) {
            contextDoc.append("hostPids", new Document(taskContext.hostPids()));
        }
        Document update = new Document("$set", new Document("conversationId", conversationId)
                .append("taskContext", contextDoc));
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
}
