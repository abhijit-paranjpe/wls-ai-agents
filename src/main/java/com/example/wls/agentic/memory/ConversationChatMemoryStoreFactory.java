package com.example.wls.agentic.memory;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.helidon.config.Config;

public final class ConversationChatMemoryStoreFactory {

    private ConversationChatMemoryStoreFactory() {
    }

    public static ChatMemoryStore create(Config config) {
        Config memoryConfig = config.get("memory");
        String provider = memoryConfig.get("provider").asString().orElse("in-memory");

        return switch (provider.toLowerCase()) {
            case "redis" -> new RedisConversationChatMemoryStore(memoryConfig);
            case "mongo", "mongodb" -> new MongoConversationChatMemoryStore(memoryConfig);
            case "in-memory", "inmemory" -> new InMemoryConversationChatMemoryStore();
            default -> throw new IllegalArgumentException("Unsupported chat memory provider: " + provider);
        };
    }
}