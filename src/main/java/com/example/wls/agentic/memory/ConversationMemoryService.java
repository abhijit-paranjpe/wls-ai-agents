package com.example.wls.agentic.memory;

import io.helidon.config.Config;
import io.helidon.service.registry.Service;

@Service.Singleton
public class ConversationMemoryService {

    private final ConversationMemoryStore store;

    public ConversationMemoryService() {
        this.store = ConversationMemoryStoreFactory.create(Config.global());
    }

    public ConversationMemoryStore store() {
        return store;
    }
}
