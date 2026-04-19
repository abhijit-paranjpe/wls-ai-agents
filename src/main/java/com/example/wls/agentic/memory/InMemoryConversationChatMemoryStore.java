package com.example.wls.agentic.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryConversationChatMemoryStore implements ChatMemoryStore {

    private final ConcurrentMap<Object, List<ChatMessage>> messagesByConversationId = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        if (memoryId == null) {
            return List.of();
        }
        return new ArrayList<>(messagesByConversationId.getOrDefault(memoryId, List.of()));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (memoryId == null) {
            return;
        }
        messagesByConversationId.put(memoryId, new ArrayList<>(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        if (memoryId == null) {
            return;
        }
        messagesByConversationId.remove(memoryId);
    }
}