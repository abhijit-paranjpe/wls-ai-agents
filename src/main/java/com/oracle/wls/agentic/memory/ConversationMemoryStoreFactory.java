package com.oracle.wls.agentic.memory;

import io.helidon.config.Config;

public final class ConversationMemoryStoreFactory {

    private ConversationMemoryStoreFactory() {
    }

    public static ConversationMemoryStore create(Config config) {
        Config memoryConfig = config.get("memory");
        String provider = memoryConfig.get("provider").asString().orElse("in-memory");

        return switch (provider.toLowerCase()) {
            case "redis" -> new RedisConversationMemoryStore(memoryConfig);
            case "mongo", "mongodb" -> new MongoConversationMemoryStore(memoryConfig);
            case "in-memory", "inmemory" -> new InMemoryConversationMemoryStore();
            default -> throw new IllegalArgumentException("Unsupported memory provider: " + provider);
        };
    }
}
