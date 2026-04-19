package com.example.wls.agentic.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

final class ChatMessageJsonCodec {

    private ChatMessageJsonCodec() {
    }

    static String serialize(List<ChatMessage> messages) {
        JsonArrayBuilder arrayBuilder = Json.createArrayBuilder();
        for (ChatMessage message : messages) {
            arrayBuilder.add(toJson(message));
        }
        return arrayBuilder.build().toString();
    }

    static List<ChatMessage> deserialize(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        try (JsonReader reader = Json.createReader(new StringReader(raw))) {
            List<ChatMessage> messages = new ArrayList<>();
            for (JsonValue value : reader.readArray()) {
                if (value.getValueType() == JsonValue.ValueType.OBJECT) {
                    messages.add(fromJson(value.asJsonObject()));
                }
            }
            return messages;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static JsonObject toJson(ChatMessage message) {
        JsonObjectBuilder builder = Json.createObjectBuilder()
                .add("type", message.type().name());

        if (message instanceof UserMessage userMessage) {
            builder.add("text", safe(userMessage.singleText()));
        } else if (message instanceof AiMessage aiMessage) {
            builder.add("text", safe(aiMessage.text()));
        } else if (message instanceof SystemMessage systemMessage) {
            builder.add("text", safe(systemMessage.text()));
        } else {
            builder.add("text", safe(message.toString()));
        }

        return builder.build();
    }

    private static ChatMessage fromJson(JsonObject object) {
        String type = object.getString("type", "USER");
        String text = object.getString("text", "");

        return switch (type) {
            case "AI" -> AiMessage.from(text);
            case "SYSTEM" -> SystemMessage.from(text);
            case "USER" -> UserMessage.from(text);
            default -> SystemMessage.from(text);
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}