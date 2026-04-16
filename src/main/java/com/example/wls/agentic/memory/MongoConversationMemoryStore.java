package com.example.wls.agentic.memory;

import com.example.wls.agentic.dto.TaskContext;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.helidon.config.Config;
import org.bson.Document;

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
                null,
                t.getString("environment"),
                t.getString("riskLevel"),
                null,
                null,
                t.getString("constraints"),
                t.getString("memorySummary")));
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
                .append("constraints", taskContext.constraints())
                .append("memorySummary", taskContext.memorySummary());
        Document update = new Document("$set", new Document("conversationId", conversationId)
                .append("taskContext", contextDoc));
        collection.updateOne(filter, update, new com.mongodb.client.model.UpdateOptions().upsert(true));
    }
}
