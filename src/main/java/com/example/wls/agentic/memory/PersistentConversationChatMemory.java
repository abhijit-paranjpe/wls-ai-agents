package com.example.wls.agentic.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PersistentConversationChatMemory implements ChatMemory {

    private final Object id;
    private final ChatMemoryStore store;

    public PersistentConversationChatMemory(Object id, ChatMemoryStore store) {
        this.id = id;
        this.store = store;
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public void add(ChatMessage message) {
        List<ChatMessage> messages = new ArrayList<>(store.getMessages(id));

        if (message instanceof SystemMessage) {
            Optional<SystemMessage> existingSystemMessage = SystemMessage.findFirst(messages);
            if (existingSystemMessage.isPresent()) {
                if (existingSystemMessage.get().equals(message)) {
                    return;
                }
                messages.remove(existingSystemMessage.get());
            }
        }

        messages.add(message);
        store.updateMessages(id, messages);
    }

    @Override
    public List<ChatMessage> messages() {
        return new ArrayList<>(store.getMessages(id));
    }

    @Override
    public void clear() {
        store.deleteMessages(id);
    }
}