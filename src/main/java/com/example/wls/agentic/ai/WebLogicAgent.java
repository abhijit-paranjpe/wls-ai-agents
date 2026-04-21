package com.example.wls.agentic.ai;

import com.example.wls.agentic.dto.AgentResponse;
import com.example.wls.agentic.dto.TaskContext;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import io.helidon.integrations.langchain4j.Ai;

import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.langchain4j.agentic.declarative.Output;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.V;

@Ai.Agent("wls-expert")
public interface WebLogicAgent {

    Pattern TASK_CONTEXT_BLOCK_PATTERN = Pattern.compile("(?s)```TASK_CONTEXT\\s*(\\{.*?})\\s*```");

    @SequenceAgent(outputKey = "jsonResponse", subAgents = {
            RequestClassifierAgent.class,
            RequestRouterAgent.class,
            SummarizerAgent.class
    })
    @SystemMessage("""
            You are a WebLogic expert assistant.
            
            Help with domain configuration, domain runtime operations, patching, and diagnostic/troubleshooting.

            Use the following conversation summary to keep context and maintain continuity:
            {{previousSummary}}

            Use the following recent conversation transcript as the primary conversation memory:
            {{conversationTranscript}}

            Use this structured task context to tailor responses and decisions:
            {{taskContext}}

            The task context is persisted across turns by conversationId/taskId/userId.
            Treat targetDomain in task context as authoritative for follow-up requests unless the user explicitly switches domain.
            Reuse targetDomain from task context when the user asks follow-up operations without explicitly naming a domain.
            If confirmTargetOnImplicitReuse is true, explicitly confirm the inferred domain before risky operations.
            If the request indicates a continuation of a pending workflow, preserve that workflow context
            and continue it instead of falling back to general assistance.
            If a pending workflow was already confirmed by the user, do not ask to reconfirm the domain
            or overall intent when task context already contains them.
            """)
    AgentResponse chat(@V("question") String question,
                       @V("previousSummary") String previousSummary,
                       @V("conversationTranscript") String conversationTranscript,
                       @V("taskContext") String taskContext,
                       @V("taskContextObject") TaskContext taskContextObject);

    /**
     * Combine the outputs of the sub-agents into a single {@link AgentResponse}.
     *
     * The internal agentic pipeline uses the shared key {@code lastResponse} across the
     * routed expert agents and the summarizer. That key must therefore keep a single,
     * consistent Java type throughout the declarative graph. The routed expert agents all
     * emit plain {@link String} responses, so this output method accepts the same type and
     * derives the structured {@link AgentResponse} only at the top-level boundary.
     */
    @Output
    static AgentResponse createResponse(@V("lastResponse") String lastResponse,
                                        @V("nextSummary") String nextSummary,
                                        @V("intent") RequestIntent intent,
                                        @V("taskContextObject") TaskContext taskContextObject) {
        String rawResponse = lastResponse == null ? "" : lastResponse;

        TaskContext baseContext = taskContextObject == null ? TaskContext.empty() : taskContextObject;
        // Apply any structured TASK_CONTEXT block that may be present in the raw
        // response from the router.
        TaskContext enrichedContext = applyStructuredTaskContextOverrides(baseContext, rawResponse);
        // Remove the block so the user sees only the natural language response.
        String cleanedResponse = stripStructuredTaskContext(rawResponse);
        String resolvedIntent = intent == null ? enrichedContext.intent() : intent.name();
        TaskContext finalContext = enrichedContext
                .withIntent(resolvedIntent)
                .withMemorySummary(nextSummary);

        return new AgentResponse(cleanedResponse, nextSummary, finalContext);
    }

    private static TaskContext applyStructuredTaskContextOverrides(TaskContext baseContext, String lastResponse) {
        JsonObject overrides = extractStructuredTaskContext(lastResponse);
        if (overrides == null) {
            return baseContext;
        }
        return new TaskContext(
                firstNonBlank(getString(overrides, "taskId"), baseContext.taskId()),
                firstNonBlank(getString(overrides, "conversationId"), baseContext.conversationId()),
                firstNonBlank(getString(overrides, "userId"), baseContext.userId()),
                firstNonBlank(getString(overrides, "intent"), baseContext.intent()),
                coalesceWorkflowString(getString(overrides, "targetDomain"), baseContext.targetDomain()),
                coalesceWorkflowString(getString(overrides, "targetServers"), baseContext.targetServers()),
                coalesceWorkflowString(getString(overrides, "targetHosts"), baseContext.targetHosts()),
                firstNonEmptyMap(getStringMap(overrides.getJsonObject("hostPids")), baseContext.hostPids()),
                firstNonBlank(getString(overrides, "environment"), baseContext.environment()),
                firstNonBlank(getString(overrides, "riskLevel"), baseContext.riskLevel()),
                getBoolean(overrides, "approvalRequired") != null
                        ? getBoolean(overrides, "approvalRequired")
                        : baseContext.approvalRequired(),
                getBoolean(overrides, "confirmTargetOnImplicitReuse") != null
                        ? getBoolean(overrides, "confirmTargetOnImplicitReuse")
                        : baseContext.confirmTargetOnImplicitReuse(),
                coalesceWorkflowString(getString(overrides, "constraints"), baseContext.constraints()),
                baseContext.memorySummary(),
                coalesceWorkflowString(getString(overrides, "pendingIntent"), baseContext.pendingIntent()),
                getBoolean(overrides, "awaitingFollowUp") != null
                        ? getBoolean(overrides, "awaitingFollowUp")
                        : baseContext.awaitingFollowUp(),
                coalesceWorkflowString(getString(overrides, "lastUserRequest"), baseContext.lastUserRequest()),
                coalesceWorkflowString(getString(overrides, "lastAssistantQuestion"), baseContext.lastAssistantQuestion()),
                coalesceWorkflowString(getString(overrides, "workflowType"), baseContext.workflowType()),
                coalesceWorkflowString(getString(overrides, "workflowStep"), baseContext.workflowStep()),
                coalesceWorkflowString(getString(overrides, "workflowStatus"), baseContext.workflowStatus()));
    }

    private static JsonObject extractStructuredTaskContext(String lastResponse) {
        if (lastResponse == null || lastResponse.isBlank()) {
            return null;
        }
        Matcher matcher = TASK_CONTEXT_BLOCK_PATTERN.matcher(lastResponse);
        if (!matcher.find()) {
            return null;
        }
        String json = matcher.group(1);
        if (json == null || json.isBlank()) {
            return null;
        }
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            return reader.readObject();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String stripStructuredTaskContext(String lastResponse) {
        if (lastResponse == null || lastResponse.isBlank()) {
            return lastResponse;
        }
        return TASK_CONTEXT_BLOCK_PATTERN.matcher(lastResponse).replaceAll("").trim();
    }

    private static String getString(JsonObject object, String key) {
        if (object == null || key == null || !object.containsKey(key) || object.isNull(key)) {
            return null;
        }
        return object.getString(key, "");
    }

    private static Boolean getBoolean(JsonObject object, String key) {
        if (object == null || key == null || !object.containsKey(key) || object.isNull(key)) {
            return null;
        }
        return object.getBoolean(key);
    }

    private static Map<String, String> getStringMap(JsonObject object) {
        if (object == null) {
            return null;
        }
        Map<String, String> values = new HashMap<>();
        for (String key : object.keySet()) {
            if (!object.isNull(key)) {
                values.put(key, object.getString(key, ""));
            }
        }
        return values.isEmpty() ? null : values;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private static Map<String, String> firstNonEmptyMap(Map<String, String> preferred, Map<String, String> fallback) {
        return preferred != null && !preferred.isEmpty() ? preferred : fallback;
    }

    private static String coalesceWorkflowString(String override, String fallback) {
        if (override == null) {
            return fallback;
        }
        return override.isBlank() ? null : override;
    }
}
