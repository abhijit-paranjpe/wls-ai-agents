package com.oracle.wls.agentic.memory;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.helidon.config.Config;
import io.helidon.service.registry.Service;

@Service.Singleton
public class ConversationMemoryService {

    private final ConversationMemoryStore store;
    private final ChatMemoryStore chatMemoryStore;
    private final String provider;
    private final int maxMessages;

    public ConversationMemoryService() {
        Config config = Config.global();
        this.store = ConversationMemoryStoreFactory.create(config);
        this.chatMemoryStore = ConversationChatMemoryStoreFactory.create(config);
        Config memoryConfig = config.get("memory");
        this.provider = memoryConfig.get("provider").asString().orElse("in-memory");
        this.maxMessages = memoryConfig.get("max-messages").asInt().orElse(20);
    }

    public ConversationMemoryStore store() {
        return store;
    }

    public ChatMemory chatMemory(String conversationId) {
        String memoryId = (conversationId == null || conversationId.isBlank()) ? "default" : conversationId;
        if ("in-memory".equalsIgnoreCase(provider) || "inmemory".equalsIgnoreCase(provider)) {
            return MessageWindowChatMemory.builder()
                    .id(memoryId)
                    .maxMessages(maxMessages)
                    .chatMemoryStore(chatMemoryStore)
                    .build();
        }
        return new PersistentConversationChatMemory(memoryId, chatMemoryStore);
    }
}
