package com.example.wls.agentic.memory;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.helidon.config.Config;
import org.bson.Document;

import java.util.List;

import static com.mongodb.client.model.Filters.eq;

public class MongoConversationChatMemoryStore implements ChatMemoryStore {

    private final MongoCollection<Document> collection;

    public MongoConversationChatMemoryStore(Config memoryConfig) {
        Config mongo = memoryConfig.get("providers.mongo");
        String uri = mongo.get("uri").asString().orElse("mongodb://localhost:27017");
        String databaseName = mongo.get("database").asString().orElse("wls_agentic");
        String collectionName = mongo.get("collection").asString().orElse("conversation_summaries");

        MongoClient client = MongoClients.create(uri);
        MongoDatabase db = client.getDatabase(databaseName);
        this.collection = db.getCollection(collectionName);
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        if (memoryId == null) {
            return List.of();
        }
        Document doc = collection.find(eq("conversationId", memoryId.toString())).first();
        if (doc == null) {
            return List.of();
        }
        return ChatMessageJsonCodec.deserialize(doc.getString("chatMessages"));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (memoryId == null) {
            return;
        }
        Document filter = new Document("conversationId", memoryId.toString());
        Document update = new Document("$set", new Document("conversationId", memoryId.toString())
                .append("chatMessages", ChatMessageJsonCodec.serialize(messages)));
        collection.updateOne(filter, update, new com.mongodb.client.model.UpdateOptions().upsert(true));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        if (memoryId == null) {
            return;
        }
        collection.updateOne(
                new Document("conversationId", memoryId.toString()),
                new Document("$unset", new Document("chatMessages", "")));
    }
}