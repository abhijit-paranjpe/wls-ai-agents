package com.example.wls.agentic.memory;

import com.example.wls.agentic.dto.TaskContext;
import io.helidon.config.Config;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import redis.clients.jedis.JedisPooled;

import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
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
                    getStringMap(o.getJsonObject("hostPids")),
                    getString(o, "environment"),
                    getString(o, "riskLevel"),
                    getBoolean(o, "approvalRequired"),
                    getBoolean(o, "confirmTargetOnImplicitReuse"),
                    getString(o, "constraints"),
                    getString(o, "memorySummary"),
                    getString(o, "pendingIntent"),
                    getBoolean(o, "awaitingFollowUp"),
                    getString(o, "lastUserRequest"),
                    getString(o, "lastAssistantQuestion"),
                    getString(o, "workflowType"),
                    getString(o, "workflowStep"),
                    getString(o, "workflowStatus")));
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
        JsonObjectBuilder builder = Json.createObjectBuilder()
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
                .add("pendingIntent", safe(taskContext.pendingIntent()))
                .add("lastUserRequest", safe(taskContext.lastUserRequest()))
                .add("lastAssistantQuestion", safe(taskContext.lastAssistantQuestion()))
                .add("workflowType", safe(taskContext.workflowType()))
                .add("workflowStep", safe(taskContext.workflowStep()))
                .add("workflowStatus", safe(taskContext.workflowStatus()));
        addNullableBoolean(builder, "approvalRequired", taskContext.approvalRequired());
        addNullableBoolean(builder, "confirmTargetOnImplicitReuse", taskContext.confirmTargetOnImplicitReuse());
        addNullableBoolean(builder, "awaitingFollowUp", taskContext.awaitingFollowUp());
        addNullableStringMap(builder, "hostPids", taskContext.hostPids());
        JsonObject json = builder.build();
        jedis.setex(TASK_CONTEXT_KEY_PREFIX + conversationId, ttlSeconds, json.toString());
    }

    private static String getString(JsonObject object, String key) {
        return object.containsKey(key) ? object.getString(key, "") : null;
    }

    private static Boolean getBoolean(JsonObject object, String key) {
        if (!object.containsKey(key) || object.isNull(key)) {
            return null;
        }
        return object.getBoolean(key);
    }

    private static void addNullableBoolean(JsonObjectBuilder builder, String key, Boolean value) {
        if (value != null) {
            builder.add(key, value);
        }
    }

    private static void addNullableStringMap(JsonObjectBuilder builder, String key, Map<String, String> value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        JsonObjectBuilder mapBuilder = Json.createObjectBuilder();
        value.forEach((k, v) -> mapBuilder.add(k, safe(v)));
        builder.add(key, mapBuilder.build());
    }

    private static Map<String, String> getStringMap(JsonObject object) {
        if (object == null) {
            return null;
        }
        Map<String, String> result = new HashMap<>();
        for (String key : object.keySet()) {
            if (!object.isNull(key)) {
                result.put(key, object.getString(key, ""));
            }
        }
        return result.isEmpty() ? null : result;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
