package com.example.wls.agentic.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.helidon.config.Config;
import redis.clients.jedis.JedisPooled;

import java.util.List;

public class RedisConversationChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "wls-agent:chat-messages:";

    private final JedisPooled jedis;
    private final int ttlSeconds;

    public RedisConversationChatMemoryStore(Config memoryConfig) {
        Config redis = memoryConfig.get("providers.redis");
        String host = redis.get("host").asString().orElse("localhost");
        int port = redis.get("port").asInt().orElse(6379);
        this.ttlSeconds = redis.get("ttl-seconds").asInt().orElse(86400);
        this.jedis = new JedisPooled(host, port);
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        if (memoryId == null) {
            return List.of();
        }
        return ChatMessageJsonCodec.deserialize(jedis.get(KEY_PREFIX + memoryId));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (memoryId == null) {
            return;
        }
        jedis.setex(KEY_PREFIX + memoryId, ttlSeconds, ChatMessageJsonCodec.serialize(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        if (memoryId == null) {
            return;
        }
        jedis.del(KEY_PREFIX + memoryId);
    }
}