package com.example.wls.agentic.memory;

import com.example.wls.agentic.dto.TaskContext;
import io.helidon.config.Config;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import redis.clients.jedis.JedisPooled;

import java.io.StringReader;
import java.util.Optional;

public class RedisConversationMemoryStore implements ConversationMemoryStore {

    private static final String KEY_PREFIX = "wls-agent:conversation-summary:";
    private static final String TASK_CONTEXT_KEY_PREFIX = "wls-agent:task-context:";

    private final JedisPooled jedis;
    private final int ttlSeconds;

    public RedisConversationMemoryStore(Config memoryConfig) {
        Config redis = memoryConfig.get("providers.redis");
        String host = redis.get("host").asString().orElse("localhost");
        int port = redis.get("port").asInt().orElse(6379);
        this.ttlSeconds = redis.get("ttl-seconds").asInt().orElse(86400);
        this.jedis = new JedisPooled(host, port);
    }

    @Override
    public Optional<String> loadSummary(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(jedis.get(KEY_PREFIX + conversationId));
    }

    @Override
    public Optional<TaskContext> loadTaskContext(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        String raw = jedis.get(TASK_CONTEXT_KEY_PREFIX + conversationId);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try (JsonReader reader = Json.createReader(new StringReader(raw))) {
            JsonObject o = reader.readObject();
            return Optional.of(new TaskContext(
                    getString(o, "taskId"),
                    getString(o, "conversationId"),
                    getString(o, "userId"),
                    getString(o, "intent"),
                    getString(o, "targetDomain"),
                    getString(o, "targetServers"),
                    getString(o, "targetHosts"),
                    null,
                    getString(o, "environment"),
                    getString(o, "riskLevel"),
                    null,
                    null,
                    getString(o, "constraints"),
                    getString(o, "memorySummary")));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public void saveSummary(String conversationId, String summary) {
        if (conversationId == null || conversationId.isBlank() || summary == null) {
            return;
        }
        jedis.setex(KEY_PREFIX + conversationId, ttlSeconds, summary);
    }

    @Override
    public void saveTaskContext(String conversationId, TaskContext taskContext) {
        if (conversationId == null || conversationId.isBlank() || taskContext == null) {
            return;
        }
        JsonObject json = Json.createObjectBuilder()
                .add("taskId", safe(taskContext.taskId()))
                .add("conversationId", safe(taskContext.conversationId()))
                .add("userId", safe(taskContext.userId()))
                .add("intent", safe(taskContext.intent()))
                .add("targetDomain", safe(taskContext.targetDomain()))
                .add("targetServers", safe(taskContext.targetServers()))
                .add("targetHosts", safe(taskContext.targetHosts()))
                .add("environment", safe(taskContext.environment()))
                .add("riskLevel", safe(taskContext.riskLevel()))
                .add("constraints", safe(taskContext.constraints()))
                .add("memorySummary", safe(taskContext.memorySummary()))
                .build();
        jedis.setex(TASK_CONTEXT_KEY_PREFIX + conversationId, ttlSeconds, json.toString());
    }

    private static String getString(JsonObject object, String key) {
        return object.containsKey(key) ? object.getString(key, "") : null;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
