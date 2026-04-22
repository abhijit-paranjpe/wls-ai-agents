package com.example.wls.agentic.rest;

import com.example.wls.agentic.ai.DomainRuntimeAgent;
import com.example.wls.agentic.ai.PatchingAgent;
import com.example.wls.agentic.ai.RequestIntent;
import com.example.wls.agentic.ai.WebLogicAgent;
import java.time.Instant;
import com.example.wls.agentic.dto.AgentResponse;
import com.example.wls.agentic.dto.TaskContext;
import com.example.wls.agentic.dto.TaskContexts;
import com.example.wls.agentic.dto.WorkflowHistoryRecord;
import com.example.wls.agentic.memory.ConversationMemoryService;
import com.example.wls.agentic.memory.ManagedDomainCacheService;
import com.example.wls.agentic.workflow.WorkflowOrchestratorService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import io.helidon.http.Http;
import io.helidon.service.registry.Service;
import io.helidon.webserver.http.RestServer;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.helidon.common.media.type.MediaTypes.APPLICATION_JSON_VALUE;

@RestServer.Endpoint
@Http.Path
@Service.Singleton
public class ChatBotEndpoint {

    private static final Logger LOGGER = Logger.getLogger(ChatBotEndpoint.class.getName());
    private static final int MAX_MEMORY_SUMMARY_CHARS = 3000;
    private static final int MAX_CONSTRAINTS_CHARS = 1000;
    private static final int MAX_TRANSCRIPT_CHARS = 6000;
    private static final int MAX_TRANSCRIPT_MESSAGES = 12;
    private static final Set<String> SHORT_AFFIRMATIVE_REPLIES = Set.of(
            "yes", "y", "yep", "yeah", "sure", "ok", "okay", "confirm", "confirmed");
    private static final Set<String> SHORT_NEGATIVE_REPLIES = Set.of(
            "no", "n", "nope", "nah");
    private static final Set<String> CANCELLATION_REPLIES = Set.of(
            "cancel", "never mind", "nevermind", "stop", "forget it");
    private static final String PATCHING_WORKFLOW_TYPE = "PATCHING";

    private static final String PATCH_APPLY_OPERATION = "APPLY_PATCHES";
    private static final String PATCH_ROLLBACK_OPERATION = "ROLLBACK_PATCHES";
    private static final String PATCH_APPLY_COMMAND = "/apply-patches";
    private static final String PATCH_ROLLBACK_COMMAND = "/rollback-patches";

    private static final Pattern DOMAIN_MENTION_PATTERN = Pattern.compile(
            "(?i)\\b(?:for|in|on|of)?\\s*domain\\s+([A-Za-z0-9._-]+)");

    private static final Pattern DOMAIN_TOKEN_SHAPE_PATTERN = Pattern.compile(
            "(?i)\\b([A-Za-z0-9][A-Za-z0-9._-]*(?:_domain|-domain))\\b");

    private static final Set<String> NON_DOMAIN_KEYWORDS = Set.of(
            "overview", "status", "health", "details", "detail", "info", "information",
            "summary", "view", "list", "all", "name", "names", "servers", "server",
            "start", "stop", "restart", "shutdown", "graceful", "domain",
            "the", "a", "an", "this", "that", "these", "those", "it", "my", "your", "our", "their",
            "for", "which", "who", "what", "when", "where", "please", "help", "with", "from", "to");

    private static final Pattern SERVERS_MENTION_PATTERN = Pattern.compile(
            "(?i)\\bservers?\\s+([A-Za-z0-9._-]+(?:\\s*,\\s*[A-Za-z0-9._-]+)*)");

    private static final Pattern HOSTS_MENTION_PATTERN = Pattern.compile(
            "(?i)\\bhosts?\\s+([A-Za-z0-9._-]+(?:\\s*,\\s*[A-Za-z0-9._-]+)*)");

    private static final Pattern PID_PATTERN = Pattern.compile("(?i)\\bpid\\s*[:=]?\\s*(\\d+)\\b");
    private static final Pattern HOST_PATTERN = Pattern.compile("(?i)\\bhost\\s*[:=]?\\s*([A-Za-z0-9._-]+)\\b");

    private final WebLogicAgent agent;
    private final ConversationMemoryService conversationMemoryService;
    private final ManagedDomainCacheService managedDomainCacheService;
    private final WorkflowOrchestratorService workflowOrchestratorService;

    private final DomainRuntimeAgent domainRuntimeAgent;
    private final PatchingAgent patchingAgent;

    @Service.Inject
    public ChatBotEndpoint(WebLogicAgent agent,
                           ConversationMemoryService conversationMemoryService,
                           ManagedDomainCacheService managedDomainCacheService,
                           WorkflowOrchestratorService workflowOrchestratorService,
                           DomainRuntimeAgent domainRuntimeAgent,
                           PatchingAgent patchingAgent) {
        this.agent = agent;
        this.conversationMemoryService = conversationMemoryService;
        this.managedDomainCacheService = managedDomainCacheService;
        this.workflowOrchestratorService = workflowOrchestratorService;
        this.domainRuntimeAgent = domainRuntimeAgent;
        this.patchingAgent = patchingAgent;
    }

    @Http.POST
    @Http.Path("/chat")
    @Http.Produces(APPLICATION_JSON_VALUE)
    public AgentResponse chatWithAssistant(@Http.Entity String rawBody) {
        AgentResponse msg = parseIncomingBody(rawBody);
        logContext("parsed-request", msg.taskContext());

        TaskContext incomingContext = msg.taskContext() == null ? TaskContext.empty() : msg.taskContext();
        TaskContext identifiedIncomingContext = ensureConversationId(incomingContext);

        String memoryKey = resolveMemoryKey(identifiedIncomingContext);
        TaskContext persistedContext = loadPersistedTaskContext(memoryKey);
        LOGGER.log(Level.FINE, "Loaded persisted context for memory key: {0}", memoryKey);
        TaskContext context = mergeContexts(persistedContext, identifiedIncomingContext);

        if (isBlank(context.conversationId()) && !isBlank(memoryKey)) {
            context = new TaskContext(
                    context.taskId(),
                    memoryKey,
                    context.userId(),
                    context.intent(),
                    context.targetDomain(),
                    context.targetServers(),
                    context.targetHosts(),
                    context.hostPids(),
                    context.environment(),
                    context.riskLevel(),
                    context.approvalRequired(),
                    context.confirmTargetOnImplicitReuse(),
                    context.constraints(),
                    context.memorySummary(),
                    context.pendingIntent(),
                    context.awaitingFollowUp(),
                    context.lastUserRequest(),
                    context.lastAssistantQuestion(),
                    context.workflowType(),
                    context.workflowStep(),
                    context.workflowStatus(),
                    context.failureReason());
        }

        context = context.withMemorySummary(firstNonBlank(
                incomingContext.memorySummary(),
                msg.summary(),
                persistedContext == null ? null : persistedContext.memorySummary(),
                context.memorySummary()));

        context = workflowOrchestratorService.applyStoredWorkflowState(context);


        LOGGER.log(Level.FINE, "**Merged task context before enrichment: {0}", context);

        String question = nonEmpty(msg.message(), "");
        WorkflowShortcut workflowShortcut = detectWorkflowShortcut(question);
        if (workflowShortcut != null) {
            question = workflowShortcut.rewrittenQuestion();
            context = context.withIntent(RequestIntent.WORKFLOW_REQUEST.name())
                    .withWorkflow(workflowShortcut.workflowType(),
                            firstNonBlank(context.workflowStep(), "INIT"),
                            firstNonBlank(context.workflowStatus(), "REQUESTED"));
        }

        List<String> managedDomains = managedDomainCacheService.getDomains();
        context = clearObsoleteWorkflowStateForIncomingTurn(question, context, managedDomains);
        context = applyWorkflowContextFromQuestion(question, context);
        context = applyDomainContextFromQuestion(question, context, managedDomains);
        context = applyServerContextFromQuestion(question, context);
        context = applyHostContextFromQuestion(question, context);
        logContext("after-enrichment", context);

        String persistedSummary = loadPersistedSummary(memoryKey);
        String summary = firstNonBlank(msg.summary(), context.memorySummary(), persistedSummary, "");

        AgentResponse asyncTrackingResponse = maybeHandleAsyncPidTracking(question, context, summary, memoryKey);
        if (asyncTrackingResponse != null) {
            savePersistedSummary(asyncTrackingResponse.taskContext(), asyncTrackingResponse.summary());
            savePersistedTaskContext(asyncTrackingResponse.taskContext());
            workflowOrchestratorService.syncWorkflowState(asyncTrackingResponse.taskContext());
            logContext("async-tracking-response", asyncTrackingResponse.taskContext());
            return asyncTrackingResponse;
        }

        AgentResponse workflowResponse = workflowOrchestratorService.handleWorkflowTurn(question, context, summary);
        if (workflowResponse != null) {
            appendConversationTurn(memoryKey, question, workflowResponse.message());
            savePersistedSummary(workflowResponse.taskContext(), workflowResponse.summary());
            savePersistedTaskContext(workflowResponse.taskContext());
            logContext("workflow-response", workflowResponse.taskContext());
            return workflowResponse;
        }

        String followUpAwareQuestion = maybeRewritePendingFollowUpQuestion(question, context, managedDomains);
        String contextualizedQuestion = maybeApplyImplicitDomainContext(followUpAwareQuestion, context, managedDomains);

        String conversationTranscript = renderTranscript(loadConversationMessages(memoryKey));
        TaskContext compactedContext = compactContext(context);
        String compactedSummary = truncate(summary, MAX_MEMORY_SUMMARY_CHARS);

        LOGGER.log(Level.FINE,
                "Memory lookup key={0}, incomingDomain={1}, persistedDomain={2}, mergedDomain={3}",
                new Object[]{
                        safe(memoryKey),
                        safe(identifiedIncomingContext.targetDomain()),
                        persistedContext == null ? "" : safe(persistedContext.targetDomain()),
                        safe(context.targetDomain())
                });

        try {
            AgentResponse response = agent.chat(
                    contextualizedQuestion,
                    compactedSummary,
                    conversationTranscript,
                    TaskContexts.toPromptContext(compactedContext),
                    compactedContext);
            TaskContext finalResponseContext = finalizeResponseTaskContext(
                    context,
                    response.taskContext(),
                    question,
                    response.message(),
                    managedDomains);
            String responseMessage = response.message();
            AgentResponse finalResponse = new AgentResponse(responseMessage, response.summary(), finalResponseContext);
            appendConversationTurn(memoryKey, question, finalResponse.message());
            savePersistedSummary(finalResponse.taskContext(), finalResponse.summary());
            savePersistedTaskContext(finalResponse.taskContext());
            workflowOrchestratorService.syncWorkflowState(finalResponse.taskContext());
            logContext("agent-response", finalResponse.taskContext());
            return finalResponse;
        } catch (RuntimeException e) {
            if (isClientUnavailableError(e)) {
                AgentResponse fallback = new AgentResponse(
                        "I can't retrieve WebLogic data right now because the WebLogic/MCP client is unavailable. "
                                + "Please verify the MCP server endpoint in application.yaml (langchain4j.mcp-clients.wls-tools-mcp-server.uri) "
                                + "and confirm the backend service is running.",
                        compactedSummary,
                        compactedContext);
                appendConversationTurn(memoryKey, question, fallback.message());
                logContext("fallback-response", fallback.taskContext());
                return fallback;
            }
            throw e;
        }
    }

    private static AgentResponse parseIncomingBody(String rawBody) {
        String body = rawBody == null ? "" : rawBody.trim();
        if (body.isBlank()) {
            return new AgentResponse("", "", TaskContext.empty());
        }

        try {
            return parseAgentResponseObject(body);
        } catch (RuntimeException ignored) {
            // fall through to salvage strategy
        }

        String wrappedObject = maybeWrapAsObject(body);
        if (wrappedObject != null) {
            try {
                return parseAgentResponseObject(wrappedObject);
            } catch (RuntimeException ignored) {
                // fall through to safe default response
            }
        }

        return new AgentResponse("", "", TaskContext.empty());
    }

    private static String maybeWrapAsObject(String body) {
        if (body.startsWith("\"") && body.contains("\":")) {
            return "{" + body + "}";
        }
        return null;
    }

    private static AgentResponse parseAgentResponseObject(String json) {
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            JsonObject object = reader.readObject();
            String message = getString(object, "message");
            String summary = getString(object, "summary");
            TaskContext context = parseTaskContextField(object);

            // Salvage malformed payload fragments that contain task context fields at root level.
            if (context == null && looksLikeTaskContextShape(object)) {
                context = parseTaskContext(object);
            }

            // If summary is missing, use memory summary from context as continuity fallback.
            if ((summary == null || summary.isBlank()) && context != null && context.memorySummary() != null) {
                summary = context.memorySummary();
            }

            return new AgentResponse(message, summary, context);
        }
    }

    private static TaskContext parseTaskContextField(JsonObject object) {
        if (object == null || !object.containsKey("taskContext") || object.isNull("taskContext")) {
            return null;
        }

        JsonValue value = object.get("taskContext");
        if (value.getValueType() == JsonValue.ValueType.OBJECT) {
            return parseTaskContext(object.getJsonObject("taskContext"));
        }

        if (value.getValueType() == JsonValue.ValueType.STRING) {
            String raw = ((JsonString) value).getString();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try (JsonReader nestedReader = Json.createReader(new StringReader(raw))) {
                JsonObject nested = nestedReader.readObject();
                return parseTaskContext(nested);
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        return null;
    }

    private static TaskContext parseTaskContext(JsonObject taskContextObject) {
        if (taskContextObject == null) {
            return null;
        }
        return new TaskContext(
                getString(taskContextObject, "taskId"),
                getString(taskContextObject, "conversationId"),
                getString(taskContextObject, "userId"),
                getString(taskContextObject, "intent"),
                getString(taskContextObject, "targetDomain"),
                getString(taskContextObject, "targetServers"),
                getString(taskContextObject, "targetHosts"),
                getStringMap(taskContextObject.getJsonObject("hostPids")),
                getString(taskContextObject, "environment"),
                getString(taskContextObject, "riskLevel"),
                getBoolean(taskContextObject, "approvalRequired"),
                getBoolean(taskContextObject, "confirmTargetOnImplicitReuse"),
                getString(taskContextObject, "constraints"),
                getString(taskContextObject, "memorySummary"),
                getString(taskContextObject, "pendingIntent"),
                getBoolean(taskContextObject, "awaitingFollowUp"),
                getString(taskContextObject, "lastUserRequest"),
                getString(taskContextObject, "lastAssistantQuestion"),
                getString(taskContextObject, "workflowType"),
                getString(taskContextObject, "workflowStep"),
                getString(taskContextObject, "workflowStatus"),
                null);
    }

    private static String getString(JsonObject obj, String key) {
        return obj != null && obj.containsKey(key) && !obj.isNull(key) ? obj.getString(key, "") : null;
    }

    private static Boolean getBoolean(JsonObject obj, String key) {
        if (obj == null || !obj.containsKey(key) || obj.isNull(key)) {
            return null;
        }
        return obj.getBoolean(key);
    }

    private static boolean looksLikeTaskContextShape(JsonObject obj) {
        return obj.containsKey("taskId")
                || obj.containsKey("conversationId")
                || obj.containsKey("targetDomain")
                || obj.containsKey("targetServers")
                || obj.containsKey("targetHosts")
                || obj.containsKey("hostPids")
                || obj.containsKey("memorySummary")
                || obj.containsKey("confirmTargetOnImplicitReuse")
                || obj.containsKey("pendingIntent")
                || obj.containsKey("awaitingFollowUp")
                || obj.containsKey("lastUserRequest")
                || obj.containsKey("lastAssistantQuestion")
                || obj.containsKey("workflowType")
                || obj.containsKey("workflowStep")
                || obj.containsKey("workflowStatus");
    }

    private static String nonEmpty(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String loadPersistedSummary(String conversationId) {
        return conversationMemoryService.store().loadSummary(conversationId).orElse("");
    }

    private void savePersistedSummary(TaskContext context, String summary) {
        if (context == null) {
            return;
        }
        conversationMemoryService.store().saveSummary(context.conversationId(), summary);
    }

    private TaskContext loadPersistedTaskContext(String conversationId) {
        return conversationMemoryService.store().loadTaskContext(conversationId).orElse(null);
    }

    private List<ChatMessage> loadConversationMessages(String conversationId) {
        if (isBlank(conversationId)) {
            return List.of();
        }
        return conversationMemoryService.chatMemory(conversationId).messages();
    }

    private void appendConversationTurn(String conversationId, String userMessage, String assistantMessage) {
        if (isBlank(conversationId)) {
            return;
        }
        ChatMemory chatMemory = conversationMemoryService.chatMemory(conversationId);
        if (!isBlank(userMessage)) {
            chatMemory.add(UserMessage.from(userMessage));
        }
        if (!isBlank(assistantMessage)) {
            chatMemory.add(AiMessage.from(assistantMessage));
        }
    }

    private void savePersistedTaskContext(TaskContext taskContext) {
        if (taskContext == null) {
            return;
        }
        conversationMemoryService.store().saveTaskContext(taskContext.conversationId(), taskContext);
    }

    private static String renderTranscript(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        int fromIndex = Math.max(0, messages.size() - MAX_TRANSCRIPT_MESSAGES);
        StringBuilder transcript = new StringBuilder();
        for (ChatMessage message : messages.subList(fromIndex, messages.size())) {
            if (message instanceof UserMessage userMessage) {
                appendTranscriptLine(transcript, "User", userMessage.singleText());
            } else if (message instanceof AiMessage aiMessage) {
                appendTranscriptLine(transcript, "Assistant", aiMessage.text());
            } else if (message instanceof SystemMessage systemMessage) {
                appendTranscriptLine(transcript, "System", systemMessage.text());
            } else {
                appendTranscriptLine(transcript, message.type().name(), message.toString());
            }
        }

        return truncate(transcript.toString().trim(), MAX_TRANSCRIPT_CHARS);
    }

    private static void appendTranscriptLine(StringBuilder transcript, String role, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!transcript.isEmpty()) {
            transcript.append('\n');
        }
        transcript.append(role).append(": ").append(text.trim());
    }

    private static TaskContext mergeContexts(TaskContext persisted, TaskContext incoming) {
        LOGGER.log(Level.INFO, "Merging contexts. Incoming: {0}, Persisted: {1}", new Object[]{incoming, persisted});

        if (persisted == null) {
            return incoming == null ? TaskContext.empty() : incoming;
        }
        if (incoming == null) {
            return persisted;
        }
        return new TaskContext(
                firstNonBlank(incoming.taskId(), persisted.taskId()),
                firstNonBlank(incoming.conversationId(), persisted.conversationId()),
                firstNonBlank(incoming.userId(), persisted.userId()),
                firstNonBlank(incoming.intent(), persisted.intent()),
                firstNonBlank(incoming.targetDomain(), persisted.targetDomain()),
                firstNonBlank(incoming.targetServers(), persisted.targetServers()),
                firstNonBlank(incoming.targetHosts(), persisted.targetHosts()),
                incoming.hostPids() != null ? incoming.hostPids() : persisted.hostPids(),
                firstNonBlank(incoming.environment(), persisted.environment()),
                firstNonBlank(incoming.riskLevel(), persisted.riskLevel()),
                incoming.approvalRequired() != null ? incoming.approvalRequired() : persisted.approvalRequired(),
                incoming.confirmTargetOnImplicitReuse() != null
                        ? incoming.confirmTargetOnImplicitReuse()
                        : persisted.confirmTargetOnImplicitReuse(),
                firstNonBlank(incoming.constraints(), persisted.constraints()),
                firstNonBlank(incoming.memorySummary(), persisted.memorySummary()),
                firstNonBlank(incoming.pendingIntent(), persisted.pendingIntent()),
                incoming.awaitingFollowUp() != null ? incoming.awaitingFollowUp() : persisted.awaitingFollowUp(),
                firstNonBlank(incoming.lastUserRequest(), persisted.lastUserRequest()),
                firstNonBlank(incoming.lastAssistantQuestion(), persisted.lastAssistantQuestion()),
                firstNonBlank(incoming.workflowType(), persisted.workflowType()),
                firstNonBlank(incoming.workflowStep(), persisted.workflowStep()),
                firstNonBlank(incoming.workflowStatus(), persisted.workflowStatus()),
                firstNonBlank(incoming.failureReason(), persisted.failureReason()));
    }

    private String resolveMemoryKey(TaskContext context) {
        if (context == null) {
            return null;
        }
        String key = firstNonBlank(context.conversationId(), context.taskId(), context.userId());
        if (isBlank(key)) {
            LOGGER.log(Level.FINE,
                    "No conversationId/taskId/userId provided; cross-turn context persistence cannot be applied.");
        }else {
            LOGGER.log(Level.INFO, "Resolved memory key for context persistence: {0}", key);
        }
        return key;
    }

    private static TaskContext ensureConversationId(TaskContext context) {
        TaskContext base = context == null ? TaskContext.empty() : context;
        if (!isBlank(base.conversationId())) {
            return base;
        }

        String generatedConversationId = "conv-" + UUID.randomUUID();
        return new TaskContext(
                base.taskId(),
                generatedConversationId,
                base.userId(),
                base.intent(),
                base.targetDomain(),
                base.targetServers(),
                base.targetHosts(),
                base.hostPids(),
                base.environment(),
                base.riskLevel(),
                base.approvalRequired(),
                base.confirmTargetOnImplicitReuse(),
                base.constraints(),
                base.memorySummary(),
                base.pendingIntent(),
                base.awaitingFollowUp(),
                base.lastUserRequest(),
                base.lastAssistantQuestion(),
                base.workflowType(),
                base.workflowStep(),
                base.workflowStatus(),
                base.failureReason());
    }

    private static TaskContext applyWorkflowContextFromQuestion(String question, TaskContext context) {
        TaskContext safeContext = context == null ? TaskContext.empty() : context;
        String workflowType = inferWorkflowType(question, safeContext.workflowType());
        if (isBlank(workflowType)) {
            return safeContext;
        }
        return safeContext.withIntent(RequestIntent.WORKFLOW_REQUEST.name())
                .withWorkflow(
                        workflowType,
                        firstNonBlank(safeContext.workflowStep(), "INIT"),
                        firstNonBlank(safeContext.workflowStatus(), "REQUESTED"));
    }

    private TaskContext applyDomainContextFromQuestion(String question, TaskContext context, List<String> managedDomains) {
        String detectedDomain = detectMentionedDomain(question, managedDomains);
        if (detectedDomain != null && !detectedDomain.isBlank()) {
            if (!isBlank(context.targetDomain()) && !context.targetDomain().equalsIgnoreCase(detectedDomain)) {
                return flushContextForDomainChange(context, detectedDomain);
            }
            return context.withTargetDomain(detectedDomain);
        }

        if (!shouldInferDomainFromQuestion(question)) {
            if (isBlank(context.targetDomain()) && managedDomains.size() == 1) {
                String onlyDomain = managedDomains.get(0);
                LOGGER.log(Level.FINE, "Auto-selected single managed domain from cache: {0}", onlyDomain);
                return context.withTargetDomain(onlyDomain);
            }
            return context;
        }

        if (isBlank(context.targetDomain()) && managedDomains.size() == 1) {
            String onlyDomain = managedDomains.get(0);
            LOGGER.log(Level.FINE, "Auto-selected single managed domain from cache: {0}", onlyDomain);
            return context.withTargetDomain(onlyDomain);
        }
        return context;
    }

    private static TaskContext flushContextForDomainChange(TaskContext current, String newDomain) {
        return new TaskContext(
                current.taskId(),
                current.conversationId(),
                current.userId(),
                current.intent(),
                newDomain,
                null,
                null,
                null,
                current.environment(),
                current.riskLevel(),
                current.approvalRequired(),
                current.confirmTargetOnImplicitReuse(),
                current.constraints(),
                null,
                null,
                false,
                current.lastUserRequest(),
                null,
                null,
                null,
                null,
                null);
    }

    private static String maybeRewritePendingFollowUpQuestion(String question,
                                                              TaskContext context,
                                                              List<String> managedDomains) {
        if (question == null || question.isBlank() || context == null) {
            return question;
        }

        if (!Boolean.TRUE.equals(context.awaitingFollowUp()) || isBlank(context.pendingIntent())) {
            return question;
        }

        String normalized = normalizeReply(question);
        String pendingIntent = context.pendingIntent();
        String priorQuestion = safe(context.lastAssistantQuestion());
        String priorUserRequest = safe(context.lastUserRequest());
        String targetDomain = safe(context.targetDomain());
        String suppliedDomain = detectMentionedDomain(question, managedDomains);

        if (CANCELLATION_REPLIES.contains(normalized)) {
            return """
                    The user cancelled the pending %s workflow.
                    Previous user request: %s
                    Previous assistant follow-up question: %s
                    Acknowledge the cancellation and do not continue the pending workflow.
                    """.formatted(pendingIntent, priorUserRequest, priorQuestion).trim();
        }

        if (SHORT_AFFIRMATIVE_REPLIES.contains(normalized)) {
            if (isPendingPatchingWorkflowFollowUp(pendingIntent, context)) {
                String operationType = detectRequestedWorkflowOperationType(priorUserRequest);
                return buildPatchingWorkflowContinuationQuestion(
                        targetDomain,
                        operationType,
                        "The user already confirmed the pending patch-application workflow.",
                        "Do not ask to confirm the domain or overall intent again. Continue directly with the next unresolved workflow step."
                );
            }
            return """
                    The user answered yes to the pending %s follow-up.
                    Previous user request: %s
                    Previous assistant follow-up question: %s
                    Active target domain from task context: %s
                    Treat previously inferred values as confirmed.
                    Continue the %s workflow without asking for the domain again.
                    Ask only for any remaining missing required information.
                    """.formatted(pendingIntent, priorUserRequest, priorQuestion, targetDomain, pendingIntent).trim();
        }

        if (SHORT_NEGATIVE_REPLIES.contains(normalized)) {
            return """
                    The user answered no to the pending %s follow-up.
                    Previous user request: %s
                    Previous assistant follow-up question: %s
                    Do not assume the previously inferred values are correct.
                    Ask the user to clarify the incorrect value and provide the missing required information.
                    """.formatted(pendingIntent, priorUserRequest, priorQuestion).trim();
        }

        if (isDomainSlotFollowUpReply(question, managedDomains)) {
            if (isPendingPatchingWorkflowFollowUp(pendingIntent, context)) {
                String operationType = detectRequestedWorkflowOperationType(priorUserRequest);
                return buildPatchingWorkflowContinuationQuestion(
                        firstNonBlank(suppliedDomain, targetDomain),
                        operationType,
                        "The user supplied the target domain for the pending patch-application workflow.",
                        "Continue the existing patching workflow with this domain. Ask only for any remaining missing required confirmation or information."
                );
            }
            return """
                    The user supplied the target domain '%s' for the pending %s workflow.
                    Previous user request: %s
                    Previous assistant follow-up question: %s
                    Continue the %s workflow using target domain '%s'.
                    Ask only for any remaining missing required information.
                    """.formatted(suppliedDomain, pendingIntent, priorUserRequest, priorQuestion,
                    pendingIntent, suppliedDomain).trim();
        }

        return question;
    }

    private static boolean isPendingPatchingWorkflowFollowUp(String pendingIntent, TaskContext context) {
        if (PATCHING_WORKFLOW_TYPE.equalsIgnoreCase(safe(context.workflowType()))) {
            return true;
        }
        if (pendingIntent == null || pendingIntent.isBlank()) {
            return false;
        }
        return "PATCHING_WORKFLOW".equalsIgnoreCase(pendingIntent)
                || (RequestIntent.WORKFLOW_REQUEST.name().equalsIgnoreCase(pendingIntent)
                && PATCHING_WORKFLOW_TYPE.equalsIgnoreCase(safe(context.workflowType())));
    }

    private static String buildPatchingWorkflowContinuationQuestion(String targetDomain,
                                                                    String operationType,
                                                                    String continuationReason,
                                                                    String nextStepInstruction) {
        boolean rollbackOperation = PATCH_ROLLBACK_OPERATION.equalsIgnoreCase(operationType);
        String baseCommand = rollbackOperation ? PATCH_ROLLBACK_COMMAND : PATCH_APPLY_COMMAND;
        String command = isBlank(targetDomain) ? baseCommand : baseCommand + " " + targetDomain;
        String domainDirective = isBlank(targetDomain)
                ? "Use the target domain already present in task context if available; otherwise ask only for the missing domain."
                : "Target domain: " + targetDomain + ".";
        return """
                %s
                Continuation context: %s
                %s
                %s
                Do not restart the workflow from the beginning.
                Ask only for any still-missing required information.
                """.formatted(command, continuationReason, domainDirective, nextStepInstruction).trim();
    }

    private static TaskContext applyServerContextFromQuestion(String question, TaskContext context) {
        String detectedServers = detectMentionedTargets(question, SERVERS_MENTION_PATTERN);
        if (detectedServers == null || detectedServers.isBlank()) {
            return context;
        }
        return context.withTargetServers(detectedServers);
    }

    private static TaskContext applyHostContextFromQuestion(String question, TaskContext context) {
        String detectedHosts = detectMentionedTargets(question, HOSTS_MENTION_PATTERN);
        if (detectedHosts == null || detectedHosts.isBlank()) {
            return context;
        }
        return context.withTargetHosts(detectedHosts);
    }

    private String maybeApplyImplicitDomainContext(String question, TaskContext context, List<String> managedDomains) {
        if (question == null || question.isBlank()) {
            return "";
        }

        if (isBlank(context.targetDomain()) && asksToOperateOnGenericDomain(question)) {
            return """
                    %s

                    Context hint: No active target domain is currently known from task context.
                    Do NOT assume a domain. Ask the user to specify the domain name before executing operations.
                    """.formatted(question);
        }

        if (detectMentionedDomain(question, managedDomains) != null || isBlank(context.targetDomain())) {
            return question;
        }

        String domainReuseDirective = likelyRequiresDomainContext(question)
                ? "Domain reuse rule: The active target domain in task context must be reused for this request. "
                + "Do not ask the user to provide a domain again unless they explicitly ask to switch domains."
                : "";

        String confirmationHint = Boolean.TRUE.equals(context.confirmTargetOnImplicitReuse())
                ? "If this is not intended, ask the user to confirm or correct the domain before executing risky actions."
                : "";

        return """
                %s

                Context hint: No domain name was specified in this user message.
                Assume the active target domain is '%s' from prior conversation context.
                %s
                %s
                """.formatted(question, context.targetDomain(), domainReuseDirective, confirmationHint);
    }

    private static String detectMentionedDomain(String question, List<String> managedDomains) {
        if (question == null) {
            return null;
        }

        if (managedDomains != null && !managedDomains.isEmpty()) {
            String managedMatch = findManagedDomainMention(question, managedDomains);
            if (!isBlank(managedMatch)) {
                return managedMatch;
            }
            return null;
        }

        return detectMentionedDomainByHeuristic(question);
    }

    private static String findManagedDomainMention(String question, List<String> managedDomains) {
        String normalizedQuestion = normalizeReply(question);
        for (String managedDomain : managedDomains) {
            if (managedDomain != null && managedDomain.equalsIgnoreCase(normalizedQuestion)) {
                return managedDomain;
            }
        }

        String loweredQuestion = question.toLowerCase();
        for (String managedDomain : managedDomains) {
            if (managedDomain == null || managedDomain.isBlank()) {
                continue;
            }
            if (containsDomainToken(loweredQuestion, managedDomain.toLowerCase())) {
                return managedDomain;
            }
        }

        String simpleReplyCandidate = extractSimpleDomainReplyCandidate(question);
        if (isBlank(simpleReplyCandidate)) {
            return null;
        }
        return resolveManagedDomainCandidate(simpleReplyCandidate, managedDomains);
    }

    private static String detectMentionedDomainByHeuristic(String question) {

        Matcher matcher = DOMAIN_MENTION_PATTERN.matcher(question);
        if (matcher.find()) {
            String candidate = matcher.group(1);
            if (candidate == null || candidate.isBlank()) {
                return null;
            }

            String normalized = candidate.toLowerCase();
            if (NON_DOMAIN_KEYWORDS.contains(normalized)) {
                return null;
            }

            if (isLikelyGrammarContinuation(question, matcher.end())) {
                return null;
            }

            return candidate;
        }

        // Fallback for explicit domain tokens such as "wlsucm14c_domain"
        Matcher byShape = DOMAIN_TOKEN_SHAPE_PATTERN.matcher(question);
        if (byShape.find()) {
            String candidate = byShape.group(1);
            if (candidate != null && !candidate.isBlank()) {
                String normalized = candidate.toLowerCase();
                if (!NON_DOMAIN_KEYWORDS.contains(normalized)) {
                    return candidate;
                }
            }
        }

        return null;
    }

    private static String resolveManagedDomainCandidate(String candidate, List<String> managedDomains) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        if (managedDomains == null || managedDomains.isEmpty()) {
            return candidate;
        }

        for (String managedDomain : managedDomains) {
            if (managedDomain.equalsIgnoreCase(candidate)) {
                return managedDomain;
            }
        }

        String resolvedPrefixMatch = null;
        for (String managedDomain : managedDomains) {
            if (managedDomain.toLowerCase().startsWith(candidate.toLowerCase())) {
                if (resolvedPrefixMatch != null && !resolvedPrefixMatch.equalsIgnoreCase(managedDomain)) {
                    return null;
                }
                resolvedPrefixMatch = managedDomain;
            }
        }

        return resolvedPrefixMatch;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalizeReply(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase().replaceAll("[.!?]+$", "");
    }

    private AgentResponse maybeRunDeterministicPatchWorkflow(String question,
                                                             TaskContext context,
                                                             String summary) {
        if (!shouldRunDeterministicPatchWorkflow(question, context)) {
            return null;
        }

        String domain = context == null ? null : context.targetDomain();
        if (isBlank(domain)) {
            return null;
        }

        String stopPrompt = """
                Execute the actual stop-servers operation now for all relevant WebLogic servers in domain '%s'.
                Use the MCP tools and return only factual execution results.
                If the stop operation cannot be completed, clearly say it failed.
                """.formatted(domain).trim();
        String applyPrompt = """
                Execute the actual patch-application operation now for domain '%s'.
                Use the MCP patching tools to apply the recommended/latest applicable patches.
                Do not only recommend or plan patches.
                Return only factual execution results.
                If no applicable patches need to be applied, say that explicitly.
                If patch application fails, clearly say it failed.
                """.formatted(domain).trim();
        String startPrompt = """
                Execute the actual start-servers operation now for all relevant WebLogic servers in domain '%s'.
                Use the MCP tools and return only factual execution results.
                If the start operation cannot be completed, clearly say it failed.
                """.formatted(domain).trim();
        String verifyPrompt = """
                Verify the current patch status now for domain '%s' using the MCP patching tools.
                Return only factual verification results about the final patch state.
                If verification fails, clearly say it failed.
                """.formatted(domain).trim();

        StringBuilder details = new StringBuilder();
        String operationType = PATCH_APPLY_OPERATION;

        String stopResult = domainRuntimeAgent.analyzeRequest(stopPrompt);
        appendWorkflowStepResult(details, "Stop servers", stopResult);
        boolean stopFailed = looksLikeWorkflowStepFailure(stopResult);
        recordWorkflowHistory(domain, PATCHING_WORKFLOW_TYPE, operationType,
                stopFailed ? "STOPPING_SERVERS" : "STOPPING_SERVERS",
                stopFailed ? "FAILED" : "IN_PROGRESS",
                question,
                stopResult,
                stopFailed);
        if (stopFailed) {
            return createDeterministicPatchWorkflowResponse(
                    context,
                    summary,
                    domain,
                    "STOPPING_SERVERS",
                    "FAILED",
                    details.toString());
        }

        String applyResult = patchingAgent.analyzeRequest(applyPrompt);
        appendWorkflowStepResult(details, "Apply patches", applyResult);
        boolean applyFailed = looksLikeWorkflowStepFailure(applyResult);
        recordWorkflowHistory(domain, PATCHING_WORKFLOW_TYPE, operationType,
                applyFailed ? "APPLYING_PATCHES" : "APPLYING_PATCHES",
                applyFailed ? "FAILED" : "IN_PROGRESS",
                question,
                applyResult,
                applyFailed);

        String startResult = domainRuntimeAgent.analyzeRequest(startPrompt);
        appendWorkflowStepResult(details, "Start servers", startResult);
        boolean startFailed = looksLikeWorkflowStepFailure(startResult);
        recordWorkflowHistory(domain, PATCHING_WORKFLOW_TYPE, operationType,
                startFailed ? "STARTING_SERVERS" : "STARTING_SERVERS",
                startFailed ? "FAILED" : "IN_PROGRESS",
                question,
                startResult,
                startFailed);

        String verifyResult = patchingAgent.analyzeRequest(verifyPrompt);
        appendWorkflowStepResult(details, "Verify patch status", verifyResult);
        boolean verifyFailed = looksLikeWorkflowStepFailure(verifyResult);

        String finalStep;
        String finalStatus;
        if (applyFailed) {
            finalStep = "APPLYING_PATCHES";
            finalStatus = "FAILED";
        } else if (startFailed) {
            finalStep = "STARTING_SERVERS";
            finalStatus = "FAILED";
        } else if (verifyFailed) {
            finalStep = "VERIFYING_STATUS";
            finalStatus = "FAILED";
        } else {
            finalStep = "COMPLETED";
            finalStatus = "COMPLETED";
        }

        recordWorkflowHistory(domain, PATCHING_WORKFLOW_TYPE, operationType,
                finalStep,
                finalStatus,
                question,
                details.toString(),
                true);

        return createDeterministicPatchWorkflowResponse(
                context,
                summary,
                domain,
                finalStep,
                finalStatus,
                details.toString());
    }

    private static boolean shouldRunDeterministicPatchWorkflow(String question, TaskContext context) {
        if (!isPatchingWorkflow(context)) {
            return false;
        }

        String normalizedQuestion = normalizeReply(question);
        if (Boolean.TRUE.equals(context.awaitingFollowUp()) && isPendingPatchingWorkflowFollowUp(context.pendingIntent(), context)) {
            return SHORT_AFFIRMATIVE_REPLIES.contains(normalizedQuestion);
        }

        String workflowStep = normalizeWorkflowToken(context.workflowStep());
        return !Boolean.TRUE.equals(context.awaitingFollowUp()) && isActivePatchingStage(workflowStep);
    }

    private AgentResponse createDeterministicPatchWorkflowResponse(TaskContext context,
                                                                   String summary,
                                                                   String domain,
                                                                   String workflowStep,
                                                                   String workflowStatus,
                                                                   String details) {
        TaskContext displayContext = (context == null ? TaskContext.empty() : context)
                .withTargetDomain(domain)
                .withWorkflow(PATCHING_WORKFLOW_TYPE, workflowStep, workflowStatus);
        String progress = buildPatchingWorkflowProgressSection(displayContext);
        String message = isBlank(progress) ? details.trim() : progress + "\n\n" + details.trim();
        TaskContext responseContext = clearWorkflowAndFollowUp((context == null ? TaskContext.empty() : context).withTargetDomain(domain));
        return new AgentResponse(message, summary, responseContext);
    }

    private void recordWorkflowHistory(String domain,
                                       String workflowType,
                                       String operationType,
                                       String workflowStep,
                                       String workflowStatus,
                                       String question,
                                       String assistantMessage,
                                       boolean terminal) {
        if (isBlank(domain) || isBlank(operationType)) {
            return;
        }
        conversationMemoryService.store().saveWorkflowHistory(new WorkflowHistoryRecord(
                domain,
                workflowType,
                operationType,
                workflowStep,
                workflowStatus,
                truncate(question, MAX_CONSTRAINTS_CHARS),
                truncate(assistantMessage, MAX_MEMORY_SUMMARY_CHARS),
                Instant.now().toString(),
                terminal));
    }

    private static void appendWorkflowStepResult(StringBuilder details, String label, String result) {
        if (details == null) {
            return;
        }
        if (!details.isEmpty()) {
            details.append("\n\n");
        }
        details.append("### ").append(label).append('\n')
                .append(isBlank(result) ? "No result returned." : result.trim());
    }

    private static boolean looksLikeWorkflowStepFailure(String result) {
        if (result == null || result.isBlank()) {
            return true;
        }

        String lower = result.toLowerCase();
        if (lower.contains("no applicable patches pending") || lower.contains("no patches pending")) {
            return false;
        }

        return lower.contains(" failed")
                || lower.startsWith("failed")
                || lower.contains(" error")
                || lower.startsWith("error")
                || lower.contains("unable to")
                || lower.contains("could not")
                || lower.contains("cannot ")
                || lower.contains("exception")
                || lower.contains("unsuccessful")
                || lower.contains("timed out");
    }

    private AgentResponse maybeRespondFromWorkflowHistory(String question,
                                                          TaskContext context,
                                                          List<String> managedDomains,
                                                          String summary) {
        if (!looksLikeWorkflowStatusQuestion(question)) {
            return null;
        }

        String domain = firstNonBlank(detectMentionedDomain(question, managedDomains), context.targetDomain());
        if (isBlank(domain)) {
            return null;
        }

        String requestedOperationType = detectRequestedWorkflowOperationType(question);
        WorkflowHistoryRecord history = requestedOperationType != null
                ? conversationMemoryService.store().loadWorkflowHistory(domain, requestedOperationType).orElse(null)
                : conversationMemoryService.store().loadLatestWorkflowHistory(domain).orElse(null);
        if (history == null) {
            return null;
        }

        TaskContext responseContext = clearWorkflowAndFollowUp(context.withTargetDomain(domain));
        String message = renderWorkflowHistoryResponse(history);
        return new AgentResponse(message, summary, responseContext);
    }

    private static TaskContext finalizeResponseTaskContext(TaskContext priorContext,
                                                           TaskContext responseContext,
                                                           String rawQuestion,
                                                           String assistantMessage,
                                                           List<String> managedDomains) {
        TaskContext merged = mergeContexts(priorContext, responseContext);
        String workflowRequest = shouldPreserveWorkflowRequest(rawQuestion, priorContext, managedDomains)
                ? priorContext.lastUserRequest()
                : rawQuestion;
        merged = merged.withLastUserRequest(workflowRequest);

        if (isCancellationReply(rawQuestion)) {
            TaskContext cancelled = TaskContexts.clearPendingFollowUp(merged);
            if (!isBlank(cancelled.workflowType())) {
                return clearWorkflowAndFollowUp(cancelled);
            }
            return cancelled;
        }

        String activeIntent = resolvePendingIntentLabel(merged);
        if (shouldAwaitFollowUp(activeIntent, assistantMessage)) {
            merged = merged.withPendingFollowUp(activeIntent, true, extractAssistantFollowUpPrompt(assistantMessage));
        } else {
            merged = TaskContexts.clearPendingFollowUp(merged);
        }

        return clearInactiveWorkflowState(merged, assistantMessage);
    }

    private static String decorateWorkflowStatusMessage(String assistantMessage,
                                                        TaskContext finalContext) {
        if (!isPatchingWorkflow(finalContext)) {
            return assistantMessage;
        }

        String progressSection = buildPatchingWorkflowProgressSection(finalContext);
        if (isBlank(progressSection)) {
            return assistantMessage;
        }

        if (assistantMessage != null && assistantMessage.toLowerCase().contains("workflow progress")) {
            return assistantMessage;
        }

        if (assistantMessage == null || assistantMessage.isBlank()) {
            return progressSection;
        }

        return progressSection + "\n\n" + assistantMessage.trim();
    }

    private static boolean isPatchingWorkflow(TaskContext context) {
        return context != null && PATCHING_WORKFLOW_TYPE.equalsIgnoreCase(safe(context.workflowType()));
    }

    private static TaskContext clearObsoleteWorkflowStateForIncomingTurn(String question,
                                                                         TaskContext context,
                                                                         List<String> managedDomains) {
        if (!isPatchingWorkflow(context)) {
            return context;
        }

        String workflowStep = normalizeWorkflowToken(context.workflowStep());
        String workflowStatus = normalizeWorkflowToken(context.workflowStatus());
        if (isWorkflowTerminal(workflowStep, workflowStatus)) {
            return clearWorkflowAndFollowUp(context);
        }

        if (!isAwaitingConfirmationState(context, workflowStep, workflowStatus)) {
            return context;
        }

        if (isPendingWorkflowReply(question, managedDomains)) {
            return context;
        }

        if (inferWorkflowType(question, null) != null) {
            return context;
        }

        return clearWorkflowAndFollowUp(context);
    }

    private static boolean isPendingWorkflowReply(String question, List<String> managedDomains) {
        String normalized = normalizeReply(question);
        return SHORT_AFFIRMATIVE_REPLIES.contains(normalized)
                || SHORT_NEGATIVE_REPLIES.contains(normalized)
                || CANCELLATION_REPLIES.contains(normalized)
                || isDomainSlotFollowUpReply(question, managedDomains);
    }

    private static TaskContext clearInactiveWorkflowState(TaskContext context, String assistantMessage) {
        if (!isPatchingWorkflow(context)) {
            return context;
        }

        String workflowStep = normalizeWorkflowToken(context.workflowStep());
        String workflowStatus = normalizeWorkflowToken(context.workflowStatus());

        if (isWorkflowTerminal(workflowStep, workflowStatus) || messageSuggestsWorkflowCompletion(assistantMessage)) {
            return clearWorkflowAndFollowUp(context);
        }

        if (isAwaitingConfirmationState(context, workflowStep, workflowStatus)) {
            String activeIntent = resolvePendingIntentLabel(context);
            return shouldAwaitFollowUp(activeIntent, assistantMessage) ? context : clearWorkflowAndFollowUp(context);
        }

        if (isActivePatchingStage(workflowStep) || "IN_PROGRESS".equals(workflowStatus)) {
            return context;
        }

        return clearWorkflowAndFollowUp(context);
    }

    private static TaskContext clearWorkflowAndFollowUp(TaskContext context) {
        if (context == null) {
            return null;
        }
        return TaskContexts.clearPendingFollowUp(context).withWorkflow(null, null, null);
    }

    private void saveWorkflowHistorySnapshot(TaskContext priorContext,
                                             TaskContext agentReportedContext,
                                             TaskContext finalContext,
                                             String question,
                                             String assistantMessage) {
        WorkflowHistoryRecord record = buildWorkflowHistoryRecord(
                priorContext,
                agentReportedContext,
                finalContext,
                question,
                assistantMessage);
        if (record == null) {
            return;
        }
        conversationMemoryService.store().saveWorkflowHistory(record);
    }

    private static WorkflowHistoryRecord buildWorkflowHistoryRecord(TaskContext priorContext,
                                                                    TaskContext agentReportedContext,
                                                                    TaskContext finalContext,
                                                                    String question,
                                                                    String assistantMessage) {
        if (!shouldPersistWorkflowHistory(priorContext, agentReportedContext, finalContext, question, assistantMessage)) {
            return null;
        }

        String domain = firstNonBlank(
                finalContext == null ? null : finalContext.targetDomain(),
                agentReportedContext == null ? null : agentReportedContext.targetDomain(),
                priorContext == null ? null : priorContext.targetDomain());
        if (isBlank(domain)) {
            return null;
        }

        String operationType = inferRecordedWorkflowOperationType(question, assistantMessage, priorContext, agentReportedContext, finalContext);
        if (isBlank(operationType)) {
            return null;
        }

        String workflowType = firstNonBlank(
                agentReportedContext == null ? null : agentReportedContext.workflowType(),
                finalContext == null ? null : finalContext.workflowType(),
                priorContext == null ? null : priorContext.workflowType(),
                PATCHING_WORKFLOW_TYPE);

        String workflowStep = firstNonBlank(
                agentReportedContext == null ? null : agentReportedContext.workflowStep(),
                finalContext == null ? null : finalContext.workflowStep());
        String workflowStatus = firstNonBlank(
                agentReportedContext == null ? null : agentReportedContext.workflowStatus(),
                finalContext == null ? null : finalContext.workflowStatus());

        if (isCancellationReply(question)) {
            workflowStatus = "CANCELLED";
            workflowStep = firstNonBlank(workflowStep, "COMPLETED");
        } else if (messageSuggestsWorkflowCompletion(assistantMessage)) {
            workflowStatus = firstNonBlank(workflowStatus, "COMPLETED");
            workflowStep = firstNonBlank(workflowStep, "COMPLETED");
        }

        String normalizedStep = normalizeWorkflowToken(workflowStep);
        String normalizedStatus = normalizeWorkflowToken(workflowStatus);
        boolean terminal = isWorkflowTerminal(normalizedStep, normalizedStatus);

        return new WorkflowHistoryRecord(
                domain,
                workflowType,
                operationType,
                normalizedStep == null ? workflowStep : normalizedStep,
                normalizedStatus == null ? workflowStatus : normalizedStatus,
                truncate(question, MAX_CONSTRAINTS_CHARS),
                truncate(assistantMessage, MAX_MEMORY_SUMMARY_CHARS),
                Instant.now().toString(),
                terminal);
    }

    private static boolean shouldPersistWorkflowHistory(TaskContext priorContext,
                                                        TaskContext agentReportedContext,
                                                        TaskContext finalContext,
                                                        String question,
                                                        String assistantMessage) {
        if (isPatchingWorkflow(priorContext) || isPatchingWorkflow(agentReportedContext) || isPatchingWorkflow(finalContext)) {
            return true;
        }

        String combined = (safe(question) + "\n" + safe(assistantMessage)).toLowerCase();
        return combined.contains("rollback")
                && (combined.contains("workflow")
                || combined.contains("patch")
                || combined.contains("completed")
                || combined.contains("successful"));
    }

    private static String inferRecordedWorkflowOperationType(String question,
                                                             String assistantMessage,
                                                             TaskContext priorContext,
                                                             TaskContext agentReportedContext,
                                                             TaskContext finalContext) {
        String requestedOperation = detectRequestedWorkflowOperationType(question);
        if (!isBlank(requestedOperation)) {
            return requestedOperation;
        }

        String combined = (safe(question) + "\n"
                + safe(assistantMessage) + "\n"
                + safe(priorContext == null ? null : priorContext.lastUserRequest()) + "\n"
                + safe(agentReportedContext == null ? null : agentReportedContext.lastUserRequest()) + "\n"
                + safe(finalContext == null ? null : finalContext.lastUserRequest())).toLowerCase();
        if (combined.contains("rollback") || combined.contains("roll back")) {
            return PATCH_ROLLBACK_OPERATION;
        }
        return PATCH_APPLY_OPERATION;
    }

    private static String detectRequestedWorkflowOperationType(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }

        String lower = question.toLowerCase();
        if (lower.contains("rollback") || lower.contains("roll back")) {
            return PATCH_ROLLBACK_OPERATION;
        }
        if ((lower.contains("apply") || lower.contains("patching")) && lower.contains("patch")) {
            return PATCH_APPLY_OPERATION;
        }
        return null;
    }

    private static boolean looksLikeWorkflowStatusQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }

        String lower = question.toLowerCase();
        boolean mentionsStatus = lower.contains("status")
                || lower.contains("complete")
                || lower.contains("completed")
                || lower.contains("progress")
                || lower.contains("latest update");
        boolean mentionsWorkflow = lower.contains("workflow")
                || lower.contains("patch")
                || lower.contains("rollback")
                || lower.contains("roll back");
        return mentionsStatus && mentionsWorkflow;
    }

    private static String renderWorkflowHistoryResponse(WorkflowHistoryRecord history) {
        TaskContext historyContext = new TaskContext(
                null,
                null,
                null,
                RequestIntent.WORKFLOW_REQUEST.name(),
                history.domain(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                history.lastUserRequest(),
                null,
                history.workflowType(),
                history.workflowStep(),
                history.workflowStatus(),
                null);

        String progress = buildPatchingWorkflowProgressSection(historyContext);
        String operationLabel = PATCH_ROLLBACK_OPERATION.equalsIgnoreCase(history.operationType())
                ? "rollback patch"
                : "patch application";
        StringBuilder builder = new StringBuilder();
        if (!isBlank(progress)) {
            builder.append(progress).append("\n\n");
        }
        builder.append("Latest recorded ")
                .append(operationLabel)
                .append(" workflow status for domain `")
                .append(history.domain())
                .append("`.");
        if (!isBlank(history.updatedAt())) {
            builder.append("\n- Last updated: ").append(history.updatedAt());
        }
        if (!isBlank(history.lastAssistantMessage())) {
            builder.append("\n\nLast recorded update:\n")
                    .append(history.lastAssistantMessage().trim());
        }
        return builder.toString();
    }

    private static boolean isAwaitingConfirmationState(TaskContext context,
                                                       String workflowStep,
                                                       String workflowStatus) {
        return Boolean.TRUE.equals(context.awaitingFollowUp())
                || !isBlank(context.pendingIntent())
                || "AWAITING_USER_CONFIRMATION".equals(workflowStatus)
                || "CONFIRMATION_REQUIRED".equals(workflowStep)
                || "AWAITING_CONFIRMATION".equals(workflowStep)
                || "CONFIRM_PATCH_PLAN".equals(workflowStep);
    }

    private static boolean isActivePatchingStage(String workflowStep) {
        if (isBlank(workflowStep)) {
            return false;
        }

        return switch (workflowStep) {
            case "STOPPING_SERVERS", "STOP_SERVERS", "STOPPING_DOMAIN", "STOPPING_MANAGED_SERVERS",
                    "APPLYING_PATCHES", "APPLY_PATCHES", "PATCHING", "RUNNING_OPATCH",
                    "STARTING_SERVERS", "START_SERVERS", "STARTING_DOMAIN", "STARTING_MANAGED_SERVERS",
                    "VERIFYING_STATUS", "VERIFY_PATCH_STATUS", "VERIFYING_PATCH_STATUS", "VERIFYING", "POSTCHECK" -> true;
            default -> false;
        };
    }

    private static boolean isWorkflowTerminal(String workflowStep, String workflowStatus) {
        return isTerminalSuccessStatus(workflowStatus)
                || isTerminalFailureStatus(workflowStatus)
                || isTerminalCancelledStatus(workflowStatus)
                || "COMPLETED".equals(workflowStep)
                || "DONE".equals(workflowStep)
                || "FINISHED".equals(workflowStep)
                || "FAILED".equals(workflowStep);
    }

    private static boolean messageSuggestsWorkflowCompletion(String assistantMessage) {
        if (assistantMessage == null || assistantMessage.isBlank()) {
            return false;
        }

        String lower = assistantMessage.toLowerCase();
        return lower.contains("workflow is complete")
                || lower.contains("workflow is completed")
                || lower.contains("mark the workflow as completed")
                || lower.contains("mark the workflow completed")
                || lower.contains("domain is on the latest patches")
                || lower.contains("no applicable patches pending")
                || lower.contains("no patches pending")
                || lower.contains("rollback process appears to have been successful");
    }

    private static String buildPatchingWorkflowProgressSection(TaskContext context) {
        if (context == null) {
            return null;
        }

        String workflowStep = normalizeWorkflowToken(context.workflowStep());
        String workflowStatus = normalizeWorkflowToken(context.workflowStatus());
        if (isBlank(workflowStep) && isBlank(workflowStatus)) {
            return null;
        }

        int currentStageIndex = resolvePatchingStageIndex(workflowStep);
        boolean terminalSuccess = isTerminalSuccessStatus(workflowStatus) || "COMPLETED".equals(workflowStep);
        boolean terminalFailure = isTerminalFailureStatus(workflowStatus) || "FAILED".equals(workflowStep);
        boolean awaitingConfirmation = "AWAITING_USER_CONFIRMATION".equals(workflowStatus)
                || "CONFIRMATION_REQUIRED".equals(workflowStep);

        if (terminalSuccess) {
            currentStageIndex = 5;
        } else if (currentStageIndex < 0) {
            currentStageIndex = awaitingConfirmation ? 0 : 1;
        }

        String title = "### Workflow progress";
        if (!isBlank(context.targetDomain())) {
            title += " for `" + context.targetDomain() + "`";
        }

        String overallStatus = buildWorkflowOverallStatus(workflowStep, workflowStatus);
        StringBuilder builder = new StringBuilder(title);
        if (!isBlank(overallStatus)) {
            builder.append('\n').append('_').append(overallStatus).append('_');
        }

        String operationType = detectRequestedWorkflowOperationType(context.lastUserRequest());
        String patchStageLabel = PATCH_ROLLBACK_OPERATION.equalsIgnoreCase(operationType)
                ? "Rollback patches"
                : "Apply patches";

        builder.append('\n')
                .append("- ").append(resolvePatchingStageMarker(0, currentStageIndex, workflowStatus, terminalFailure)).append(" Confirm patch plan")
                .append('\n')
                .append("- ").append(resolvePatchingStageMarker(1, currentStageIndex, workflowStatus, terminalFailure)).append(" Stop servers")
                .append('\n')
                .append("- ").append(resolvePatchingStageMarker(2, currentStageIndex, workflowStatus, terminalFailure)).append(" ").append(patchStageLabel)
                .append('\n')
                .append("- ").append(resolvePatchingStageMarker(3, currentStageIndex, workflowStatus, terminalFailure)).append(" Start servers")
                .append('\n')
                .append("- ").append(resolvePatchingStageMarker(4, currentStageIndex, workflowStatus, terminalFailure)).append(" Verify patch status");

        return builder.toString();
    }

    private static String resolvePatchingStageMarker(int stageIndex,
                                                     int currentStageIndex,
                                                     String workflowStatus,
                                                     boolean terminalFailure) {
        if (isTerminalCancelledStatus(workflowStatus)) {
            return stageIndex < currentStageIndex ? "✅" : "🚫";
        }

        if (currentStageIndex >= 5 && isTerminalSuccessStatus(workflowStatus)) {
            return "✅";
        }

        if (stageIndex < currentStageIndex) {
            return "✅";
        }

        if (stageIndex > currentStageIndex) {
            return "⬜";
        }

        if (terminalFailure) {
            return "❌";
        }

        if ("AWAITING_USER_CONFIRMATION".equals(workflowStatus)) {
            return "🟡";
        }

        if ("COMPLETED".equals(workflowStatus) || "SUCCEEDED".equals(workflowStatus)) {
            return "✅";
        }

        return "⏳";
    }

    private static int resolvePatchingStageIndex(String workflowStep) {
        if (isBlank(workflowStep)) {
            return -1;
        }

        return switch (workflowStep) {
            case "CONFIRMATION_REQUIRED", "AWAITING_CONFIRMATION", "RESOLVE_TARGET", "INIT",
                    "REQUESTED", "INSPECTING_PATCHES", "PATCH_SELECTION", "CONFIRM_PATCH_PLAN" -> 0;
            case "STOPPING_SERVERS", "STOP_SERVERS", "STOPPING_DOMAIN", "STOPPING_MANAGED_SERVERS" -> 1;
            case "APPLYING_PATCHES", "APPLY_PATCHES", "PATCHING", "RUNNING_OPATCH",
                    "ROLLING_BACK_PATCHES", "ROLLBACK_PATCHES", "PATCH_ROLLBACK", "ROLLBACK" -> 2;
            case "STARTING_SERVERS", "START_SERVERS", "STARTING_DOMAIN", "STARTING_MANAGED_SERVERS" -> 3;
            case "VERIFYING_STATUS", "VERIFY_PATCH_STATUS", "VERIFYING_PATCH_STATUS", "VERIFYING", "POSTCHECK" -> 4;
            case "COMPLETED", "DONE", "FINISHED" -> 5;
            case "FAILED" -> 2;
            default -> -1;
        };
    }

    private static String buildWorkflowOverallStatus(String workflowStep, String workflowStatus) {
        if (isTerminalSuccessStatus(workflowStatus) || "COMPLETED".equals(workflowStep)) {
            return "Overall status: Completed";
        }
        if (isTerminalFailureStatus(workflowStatus) || "FAILED".equals(workflowStep)) {
            return "Overall status: Failed during " + humanizeWorkflowToken(workflowStep);
        }
        if (isTerminalCancelledStatus(workflowStatus)) {
            return "Overall status: Cancelled";
        }
        if ("AWAITING_USER_CONFIRMATION".equals(workflowStatus) || "CONFIRMATION_REQUIRED".equals(workflowStep)) {
            return "Overall status: Awaiting confirmation";
        }
        if (!isBlank(workflowStep)) {
            return "Overall status: In progress - " + humanizeWorkflowToken(workflowStep);
        }
        if (!isBlank(workflowStatus)) {
            return "Overall status: " + humanizeWorkflowToken(workflowStatus);
        }
        return null;
    }

    private static String normalizeWorkflowToken(String value) {
        if (isBlank(value)) {
            return null;
        }
        return value.trim().replace('-', '_').replace(' ', '_').toUpperCase();
    }

    private static String humanizeWorkflowToken(String value) {
        if (isBlank(value)) {
            return "workflow";
        }

        String normalized = value.trim().replace('-', '_').replace(' ', '_').toLowerCase();
        String[] parts = normalized.split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.isEmpty() ? "workflow" : builder.toString();
    }

    private static boolean isTerminalSuccessStatus(String workflowStatus) {
        return "COMPLETED".equals(workflowStatus) || "SUCCEEDED".equals(workflowStatus);
    }

    private static boolean isTerminalFailureStatus(String workflowStatus) {
        return "FAILED".equals(workflowStatus) || "ABORTED".equals(workflowStatus);
    }

    private static boolean isTerminalCancelledStatus(String workflowStatus) {
        return "CANCELLED".equals(workflowStatus);
    }

    private static String resolvePendingIntentLabel(TaskContext context) {
        if (context == null) {
            return null;
        }
        if (!isBlank(context.pendingIntent())) {
            return context.pendingIntent();
        }
        if (RequestIntent.WORKFLOW_REQUEST.name().equalsIgnoreCase(context.intent()) && !isBlank(context.workflowType())) {
            return context.workflowType() + "_WORKFLOW";
        }
        return context.intent();
    }

    private static boolean shouldAwaitFollowUp(String intent, String assistantMessage) {
        if (isBlank(intent) || "GENERAL_ASSISTANCE".equalsIgnoreCase(intent) || isBlank(assistantMessage)) {
            return false;
        }

        String lower = assistantMessage.toLowerCase();
        return assistantMessage.contains("?")
                || lower.contains("please confirm")
                || lower.contains("could you please confirm")
                || lower.contains("do you have specific")
                || lower.contains("once i have this information")
                || lower.contains("let me know")
                || lower.contains("which domain")
                || lower.contains("what domain")
                || lower.contains("is this the correct domain");
    }

    private static String extractAssistantFollowUpPrompt(String assistantMessage) {
        if (assistantMessage == null) {
            return null;
        }
        return truncate(assistantMessage.trim(), MAX_CONSTRAINTS_CHARS);
    }

    private static boolean isCancellationReply(String question) {
        return CANCELLATION_REPLIES.contains(normalizeReply(question));
    }

    private static boolean shouldPreserveWorkflowRequest(String rawQuestion,
                                                         TaskContext priorContext,
                                                         List<String> managedDomains) {
        if (priorContext == null || !Boolean.TRUE.equals(priorContext.awaitingFollowUp())) {
            return false;
        }
        String normalized = normalizeReply(rawQuestion);
        return SHORT_AFFIRMATIVE_REPLIES.contains(normalized)
                || SHORT_NEGATIVE_REPLIES.contains(normalized)
                || CANCELLATION_REPLIES.contains(normalized)
                || isDomainSlotFollowUpReply(rawQuestion, managedDomains);
    }

    private static boolean isDomainSlotFollowUpReply(String question, List<String> managedDomains) {
        return detectMentionedDomain(question, managedDomains) != null
                && !looksLikeStandaloneWorkflowRequest(question);
    }

    private static boolean looksLikeStandaloneWorkflowRequest(String question) {
        if (question == null) {
            return false;
        }
        String q = question.toLowerCase();
        return q.contains("patch")
                || q.contains("status")
                || q.contains("overview")
                || q.contains("details")
                || q.contains("list")
                || q.contains("view")
                || q.contains("start")
                || q.contains("stop")
                || q.contains("restart")
                || q.contains("shutdown")
                || q.contains("graceful")
                || q.contains("diagnostic")
                || q.contains("troubleshoot");
    }

    private static String extractSimpleDomainReplyCandidate(String question) {
        if (question == null) {
            return null;
        }
        String trimmed = normalizeReply(question);
        if (trimmed.isBlank()) {
            return null;
        }

        String[] parts = trimmed.split("\\s+");
        if (parts.length == 1) {
            return cleanDomainCandidate(parts[0]);
        }
        if (parts.length == 2 && "domain".equals(parts[0])) {
            return cleanDomainCandidate(parts[1]);
        }
        if (parts.length == 2 && ("use".equals(parts[0]) || "for".equals(parts[0]) || "its".equals(parts[0])
                || "it's".equals(parts[0]))) {
            return cleanDomainCandidate(parts[1]);
        }
        if (parts.length == 3 && "use".equals(parts[0]) && "domain".equals(parts[1])) {
            return cleanDomainCandidate(parts[2]);
        }
        return null;
    }

    private static String cleanDomainCandidate(String candidate) {
        if (candidate == null) {
            return null;
        }
        int start = 0;
        int end = candidate.length();
        while (start < end && !Character.isLetterOrDigit(candidate.charAt(start))) {
            start++;
        }
        while (end > start && !Character.isLetterOrDigit(candidate.charAt(end - 1))) {
            end--;
        }
        return start >= end ? null : candidate.substring(start, end);
    }

    private static boolean containsDomainToken(String text, String domain) {
        int fromIndex = 0;
        while (fromIndex >= 0 && fromIndex < text.length()) {
            int matchIndex = text.indexOf(domain, fromIndex);
            if (matchIndex < 0) {
                return false;
            }
            int matchEnd = matchIndex + domain.length();
            boolean leftBoundary = matchIndex == 0 || !isDomainCharacter(text.charAt(matchIndex - 1));
            boolean rightBoundary = matchEnd == text.length() || !isDomainCharacter(text.charAt(matchEnd));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            fromIndex = matchIndex + 1;
        }
        return false;
    }

    private static boolean isDomainCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '.' || value == '_' || value == '-';
    }

    private static boolean asksToOperateOnGenericDomain(String question) {
        String lower = question.toLowerCase();
        boolean mentionsGenericDomain = lower.contains("the domain") || lower.matches(".*\\bdomain\\b.*");
        boolean hasOperationVerb = lower.contains("start")
                || lower.contains("stop")
                || lower.contains("restart")
                || lower.contains("shutdown")
                || lower.contains("graceful");
        return mentionsGenericDomain && hasOperationVerb;
    }

    private static String detectMentionedTargets(String question, Pattern pattern) {
        if (question == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(question);
        if (!matcher.find()) {
            return null;
        }
        String captured = matcher.group(1);
        return captured == null ? null : captured.trim();
    }

    private AgentResponse maybeHandleAsyncPidTracking(String question,
                                                      TaskContext context,
                                                      String summary,
                                                      String memoryKey) {
        if (!looksLikeAsyncPidTrackingQuestion(question)) {
            return null;
        }

        String pid = detectPid(question);
        String host = firstNonBlank(detectHost(question), context == null ? null : context.targetHosts());

        if (isBlank(pid) || isBlank(host)) {
            String missing = isBlank(pid) && isBlank(host)
                    ? "PID and host"
                    : (isBlank(pid) ? "PID" : "host");
            TaskContext responseContext = context == null ? TaskContext.empty() : context;
            return new AgentResponse(
                    "I can track async job status, but I need the " + missing
                            + ". Please provide: 'Track async job status for PID <pid> on host <host>'.",
                    summary,
                    responseContext);
        }

        String trackingPrompt = """
                Track async job status for PID %s on host %s.
                Execute the relevant MCP/runtime tools now and return the current factual status.
                If the PID is not found, explicitly say so.
                """.formatted(pid, host).trim();

        String trackingResult;
        try {
            trackingResult = domainRuntimeAgent.analyzeRequest(trackingPrompt);
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "Async PID tracking request failed", ex);
            trackingResult = "Unable to fetch async job status right now for PID " + pid + " on host " + host
                    + ". Please retry in a few seconds.";
        }

        if (isBlank(trackingResult)) {
            trackingResult = "No status response was returned for PID " + pid + " on host " + host
                    + ". Please retry in a few seconds.";
        }

        TaskContext responseContext = (context == null ? TaskContext.empty() : context)
                .withTargetHosts(host)
                .withLastUserRequest(question);
        appendConversationTurn(memoryKey, question, trackingResult);
        return new AgentResponse(trackingResult, summary, responseContext);
    }

    private static boolean looksLikeAsyncPidTrackingQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String q = question.toLowerCase();
        return (q.contains("track") || q.contains("status") || q.contains("check"))
                && (q.contains("async") || q.contains("job") || q.contains("pid"));
    }

    private static String detectPid(String question) {
        if (question == null) {
            return null;
        }
        Matcher matcher = PID_PATTERN.matcher(question);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String detectHost(String question) {
        if (question == null) {
            return null;
        }
        Matcher matcher = HOST_PATTERN.matcher(question);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean isClientUnavailableError(Throwable t) {
        Throwable current = t;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String m = message.toLowerCase();
                if (m.contains("no weblogic client")
                        || m.contains("client is unavailable")
                        || m.contains("mcp") && m.contains("unavailable")
                        || m.contains("connection refused")
                        || m.contains("failed to connect")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean likelyRequiresDomainContext(String question) {
        String q = question.toLowerCase();
        return q.contains("domain")
                || q.contains("server")
                || q.contains("overview")
                || q.contains("status")
                || q.contains("start")
                || q.contains("stop")
                || q.contains("restart")
                || q.contains("shutdown");
    }

    private static boolean shouldInferDomainFromQuestion(String question) {
        if (question == null) {
            return false;
        }
        String q = question.toLowerCase();
        return q.contains("switch")
                || q.contains("use domain")
                || q.contains("for domain")
                || q.contains("in domain")
                || q.contains("on domain")
                || q.contains("start")
                || q.contains("stop")
                || q.contains("restart")
                || q.contains("shutdown")
                || q.contains("patch")
                || q.contains("view")
                || q.contains("overview")
                || q.contains("status")
                || q.contains("details")
                || q.contains("list");
    }

    private static boolean isLikelyGrammarContinuation(String question, int candidateEndIndex) {
        if (question == null || candidateEndIndex < 0 || candidateEndIndex >= question.length()) {
            return false;
        }
        String tail = question.substring(candidateEndIndex).trim().toLowerCase();
        return tail.startsWith("for ")
                || tail.startsWith("which")
                || tail.startsWith("that ")
                || tail.startsWith("who ")
                || tail.startsWith("where ")
                || tail.startsWith("when ");
    }

    private static void logContext(String stage, TaskContext context) {
        if (!LOGGER.isLoggable(Level.FINE)) {
            return;
        }
        if (context == null) {
            LOGGER.log(Level.FINE, "TaskContext stage={0} context=null", stage);
            return;
        }

        LOGGER.log(Level.FINE,
                "TaskContext stage={0} domain={1}, servers={2}, hosts={3}, workflowType={4}, workflowStep={5}, memorySummaryLen={6}",
                new Object[]{
                        stage,
                        safe(context.targetDomain()),
                        safe(context.targetServers()),
                        safe(context.targetHosts()),
                        safe(context.workflowType()),
                        safe(context.workflowStep()),
                        context.memorySummary() == null ? 0 : context.memorySummary().length()
                });
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static TaskContext compactContext(TaskContext context) {
        if (context == null) {
            return TaskContext.empty();
        }
        return new TaskContext(
                context.taskId(),
                context.conversationId(),
                context.userId(),
                context.intent(),
                context.targetDomain(),
                context.targetServers(),
                context.targetHosts(),
                context.hostPids(),
                context.environment(),
                context.riskLevel(),
                context.approvalRequired(),
                context.confirmTargetOnImplicitReuse(),
                truncate(context.constraints(), MAX_CONSTRAINTS_CHARS),
                truncate(context.memorySummary(), MAX_MEMORY_SUMMARY_CHARS),
                context.pendingIntent(),
                context.awaitingFollowUp(),
                truncate(context.lastUserRequest(), MAX_CONSTRAINTS_CHARS),
                truncate(context.lastAssistantQuestion(), MAX_CONSTRAINTS_CHARS),
                context.workflowType(),
                context.workflowStep(),
                context.workflowStatus(),
                context.failureReason());
    }

    private static WorkflowShortcut detectWorkflowShortcut(String question) {
        if (question == null) {
            return null;
        }
        String trimmed = question.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.equalsIgnoreCase("/apply-patches")) {
            return new WorkflowShortcut(PATCHING_WORKFLOW_TYPE,
                    "Apply latest recommended patches to the target domain using the patching workflow.");
        }
        if (trimmed.equalsIgnoreCase("/rollback-patches") || trimmed.equalsIgnoreCase("/rollback patches")) {
            return new WorkflowShortcut(PATCHING_WORKFLOW_TYPE,
                    "Rollback latest applied patches on the target domain using the patching workflow.");
        }
        if (trimmed.toLowerCase().startsWith("/apply-patches ")) {
            String remainder = trimmed.substring("/apply-patches".length()).trim();
            String rewritten = remainder.isBlank()
                    ? "Apply latest recommended patches to the target domain using the patching workflow."
                    : "Apply latest recommended patches using the patching workflow. Additional user input: " + remainder;
            return new WorkflowShortcut(PATCHING_WORKFLOW_TYPE, rewritten);
        }
        if (trimmed.toLowerCase().startsWith("/rollback-patches ") || trimmed.toLowerCase().startsWith("/rollback patches ")) {
            String commandPrefix = trimmed.toLowerCase().startsWith("/rollback patches ")
                    ? "/rollback patches"
                    : "/rollback-patches";
            String remainder = trimmed.substring(commandPrefix.length()).trim();
            String rewritten = remainder.isBlank()
                    ? "Rollback latest applied patches on the target domain using the patching workflow."
                    : "Rollback latest applied patches using the patching workflow. Additional user input: " + remainder;
            return new WorkflowShortcut(PATCHING_WORKFLOW_TYPE, rewritten);
        }
        return null;
    }

    private static String inferWorkflowType(String question, String existingWorkflowType) {
        if (!isBlank(existingWorkflowType)) {
            return existingWorkflowType;
        }
        if (question == null) {
            return null;
        }
        String q = question.toLowerCase().trim();
        if (q.startsWith("/apply-patches")
                || q.startsWith("/rollback-patches")
                || q.startsWith("/rollback patches")
                || looksLikePatchApplyWorkflowRequest(q)
                || looksLikePatchRollbackWorkflowRequest(q)) {
            return PATCHING_WORKFLOW_TYPE;
        }
        return null;
    }

    private static boolean looksLikePatchApplyWorkflowRequest(String q) {
        if (q == null || q.isBlank() || looksLikeInformationalPatchRequest(q)) {
            return false;
        }
        return (q.startsWith("apply ")
                || q.startsWith("please apply")
                || q.startsWith("can you apply")
                || q.startsWith("could you apply")
                || q.startsWith("i want you to apply")
                || q.startsWith("go ahead and apply")
                || q.contains(" please apply ")
                || q.contains(" can you apply ")
                || q.contains(" could you apply "))
                && q.contains("patch");
    }

    private static boolean looksLikePatchRollbackWorkflowRequest(String q) {
        if (q == null || q.isBlank() || looksLikeInformationalPatchRequest(q)) {
            return false;
        }
        boolean hasRollbackVerb = q.startsWith("rollback")
                || q.startsWith("roll back")
                || q.startsWith("please rollback")
                || q.startsWith("please roll back")
                || q.startsWith("can you rollback")
                || q.startsWith("can you roll back")
                || q.startsWith("could you rollback")
                || q.startsWith("could you roll back")
                || q.startsWith("i want you to rollback")
                || q.startsWith("i want you to roll back")
                || q.contains(" please rollback ")
                || q.contains(" please roll back ")
                || q.contains(" can you rollback ")
                || q.contains(" can you roll back ")
                || q.contains(" could you rollback ")
                || q.contains(" could you roll back ");
        return hasRollbackVerb && q.contains("patch");
    }

    private static boolean looksLikeInformationalPatchRequest(String q) {
        if (q == null || q.isBlank()) {
            return false;
        }
        return q.startsWith("is ")
                || q.startsWith("are ")
                || q.startsWith("list ")
                || q.startsWith("show ")
                || q.startsWith("what ")
                || q.startsWith("which ")
                || q.startsWith("do i have ")
                || q.startsWith("does ")
                || q.contains("patch status")
                || q.contains("on latest patches")
                || q.contains("latest patches?");
    }

    private record WorkflowShortcut(String workflowType, String rewrittenQuestion) {
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + " …[truncated]";
    }

    private static Map<String, String> getStringMap(JsonObject mapObject) {
        if (mapObject == null) {
            return null;
        }
        Map<String, String> result = new HashMap<>();
        for (String key : mapObject.keySet()) {
            if (!mapObject.isNull(key)) {
                result.put(key, mapObject.getString(key, ""));
            }
        }
        return result.isEmpty() ? null : result;
    }
}
