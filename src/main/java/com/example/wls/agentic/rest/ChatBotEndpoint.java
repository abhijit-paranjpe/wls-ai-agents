package com.example.wls.agentic.rest;

import com.example.wls.agentic.ai.DomainRuntimeAgent;
import com.example.wls.agentic.ai.PatchingAgent;
import com.example.wls.agentic.ai.WebLogicAgent;
import com.example.wls.agentic.dto.AgentResponse;
import com.example.wls.agentic.dto.TaskContext;
import com.example.wls.agentic.dto.TaskContexts;
import com.example.wls.agentic.memory.ConversationMemoryService;
import com.example.wls.agentic.memory.ManagedDomainCacheService;
import com.example.wls.agentic.workflow.ApprovalDecision;
import com.example.wls.agentic.workflow.PatchingWorkflowCoordinator;
import com.example.wls.agentic.workflow.PatchingWorkflowProposalResult;
import com.example.wls.agentic.workflow.WorkflowApprovalSemaphore;
import com.example.wls.agentic.workflow.WorkflowChannel;
import com.example.wls.agentic.workflow.WorkflowRecord;
import com.example.wls.agentic.workflow.WorkflowStatus;
import com.example.wls.agentic.workflow.WorkflowSummary;
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
import java.util.Optional;
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
    private final PatchingWorkflowCoordinator workflowCoordinator;
    private final WorkflowApprovalSemaphore approvalSemaphore;

    private final DomainRuntimeAgent domainRuntimeAgent;
    private final PatchingAgent patchingAgent;

    @Service.Inject
    public ChatBotEndpoint(WebLogicAgent agent,
                           ConversationMemoryService conversationMemoryService,
                           ManagedDomainCacheService managedDomainCacheService,
                           PatchingWorkflowCoordinator workflowCoordinator,
                           WorkflowApprovalSemaphore approvalSemaphore,
                           DomainRuntimeAgent domainRuntimeAgent,
                           PatchingAgent patchingAgent) {
        this.agent = agent;
        this.conversationMemoryService = conversationMemoryService;
        this.managedDomainCacheService = managedDomainCacheService;
        this.workflowCoordinator = workflowCoordinator;
        this.approvalSemaphore = approvalSemaphore;
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
                    context.activeWorkflowIds(),
                    context.lastReferencedWorkflowId(),
                    context.failureReason());
        }

        context = context.withMemorySummary(firstNonBlank(
                incomingContext.memorySummary(),
                msg.summary(),
                persistedContext == null ? null : persistedContext.memorySummary(),
                context.memorySummary()));


        LOGGER.log(Level.FINE, "**Merged task context before enrichment: {0}", context);

        String question = nonEmpty(msg.message(), "");

        List<String> managedDomains = managedDomainCacheService.getDomains();
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
            logContext("async-tracking-response", asyncTrackingResponse.taskContext());
            return asyncTrackingResponse;
        }

        AgentResponse workflowChatResponse = maybeHandleWorkflowChatOperation(question, context, summary, memoryKey);
        if (workflowChatResponse != null) {
            savePersistedSummary(workflowChatResponse.taskContext(), workflowChatResponse.summary());
            savePersistedTaskContext(workflowChatResponse.taskContext());
            logContext("workflow-chat-response", workflowChatResponse.taskContext());
            return workflowChatResponse;
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
                getStringList(taskContextObject, "activeWorkflowIds"),
                getString(taskContextObject, "lastReferencedWorkflowId"),
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
                || obj.containsKey("activeWorkflowIds")
                || obj.containsKey("lastReferencedWorkflowId")
                || obj.containsKey("failureReason");
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
                firstNonEmptyList(incoming.activeWorkflowIds(), persisted.activeWorkflowIds()),
                firstNonBlank(incoming.lastReferencedWorkflowId(), persisted.lastReferencedWorkflowId()),
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
        } else {
            LOGGER.log(Level.INFO, "Resolved memory key for context persistence: {0}", key);
        }
        return key;
    }

    private static TaskContext ensureConversationId(TaskContext context) {
        TaskContext base = context == null ? TaskContext.empty() : context;
        if (!isBlank(base.conversationId())) {
            return base;
        }
        return base.withConversationId("conv-" + UUID.randomUUID());
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
                current.activeWorkflowIds(),
                current.lastReferencedWorkflowId(),
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
                    The user cancelled the pending follow-up.
                    Pending intent: %s
                    Previous user request: %s
                    Previous assistant follow-up question: %s
                    Acknowledge the cancellation and do not continue the pending follow-up.
                    """.formatted(pendingIntent, priorUserRequest, priorQuestion).trim();
        }

        if (SHORT_AFFIRMATIVE_REPLIES.contains(normalized)) {
            return """
                    The user answered yes to the pending follow-up.
                    Pending intent: %s
                    Previous user request: %s
                    Previous assistant follow-up question: %s
                    Active target domain from task context: %s
                    Treat previously inferred values as confirmed.
                    Continue using the existing context and ask only for any remaining missing required information.
                    """.formatted(pendingIntent, priorUserRequest, priorQuestion, targetDomain).trim();
        }

        if (SHORT_NEGATIVE_REPLIES.contains(normalized)) {
            return """
                    The user answered no to the pending follow-up.
                    Pending intent: %s
                    Previous user request: %s
                    Previous assistant follow-up question: %s
                    Do not assume the previously inferred values are correct.
                    Ask the user to clarify the incorrect value and provide any missing required information.
                    """.formatted(pendingIntent, priorUserRequest, priorQuestion).trim();
        }

        if (isDomainSlotFollowUpReply(question, managedDomains)) {
            return """
                    The user supplied the target domain '%s' for the pending follow-up.
                    Pending intent: %s
                    Previous user request: %s
                    Previous assistant follow-up question: %s
                    Continue using target domain '%s'.
                    Ask only for any remaining missing required information.
                    """.formatted(suppliedDomain, pendingIntent, priorUserRequest, priorQuestion, suppliedDomain).trim();
        }

        return question;
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

    private static TaskContext finalizeResponseTaskContext(TaskContext priorContext,
                                                           TaskContext responseContext,
                                                           String rawQuestion,
                                                           String assistantMessage,
                                                           List<String> managedDomains) {
        TaskContext merged = mergeContexts(priorContext, responseContext);
        String requestToPersist = shouldPreserveFollowUpRequest(rawQuestion, priorContext, managedDomains)
                ? priorContext.lastUserRequest()
                : rawQuestion;
        merged = merged.withLastUserRequest(requestToPersist);

        if (isCancellationReply(rawQuestion)) {
            return TaskContexts.clearPendingFollowUp(merged);
        }

        String activeIntent = resolvePendingIntentLabel(merged);
        if (shouldAwaitFollowUp(activeIntent, assistantMessage)) {
            return merged.withPendingFollowUp(activeIntent, true, extractAssistantFollowUpPrompt(assistantMessage));
        }

        return TaskContexts.clearPendingFollowUp(merged);
    }

    private static String resolvePendingIntentLabel(TaskContext context) {
        if (context == null) {
            return null;
        }
        if (!isBlank(context.pendingIntent())) {
            return context.pendingIntent();
        }
        return context.intent();
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

    private static boolean shouldPreserveFollowUpRequest(String rawQuestion,
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
                && !looksLikeStandaloneOperationalRequest(question);
    }

    private static boolean looksLikeStandaloneOperationalRequest(String question) {
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

    private AgentResponse maybeHandleWorkflowChatOperation(String question,
                                                           TaskContext context,
                                                           String summary,
                                                           String memoryKey) {
        if (question == null || question.isBlank()) {
            return null;
        }

        String normalized = normalizeReply(question);
        if (looksLikeWorkflowListRequest(normalized)) {
            return handleWorkflowListRequest(question, context, summary, memoryKey, normalized);
        }
        if (looksLikeWorkflowStatusRequest(normalized)) {
            return handleWorkflowStatusRequest(question, context, summary, memoryKey);
        }
        if (looksLikeWorkflowApprovalRequest(normalized)) {
            return handleWorkflowApprovalRequest(question, context, summary, memoryKey, normalized);
        }
        if (looksLikeWorkflowProposalRequest(normalized)) {
            return handleWorkflowProposalRequest(question, context, summary, memoryKey);
        }
        return null;
    }

    private AgentResponse handleWorkflowProposalRequest(String question,
                                                        TaskContext context,
                                                        String summary,
                                                        String memoryKey) {
        String targetDomain = context == null ? null : context.targetDomain();
        if (isBlank(targetDomain)) {
            return new AgentResponse(
                    "I can create a patching workflow proposal, but I need the target domain. "
                            + "Please specify a domain, for example: 'Apply recommended patches to domain payments-prod'.",
                    summary,
                    context == null ? TaskContext.empty() : context);
        }

        PatchingWorkflowProposalResult proposal = workflowCoordinator.createProposal(
                targetDomain,
                safe(context.conversationId()),
                safe(context.taskId()),
                truncate(question, MAX_CONSTRAINTS_CHARS));

        WorkflowRecord workflow = proposal.workflow();
        TaskContext responseContext = (context == null ? TaskContext.empty() : context)
                .withActiveWorkflowIds(List.of(workflow.workflowId()))
                .withLastReferencedWorkflowId(workflow.workflowId())
                .withLastUserRequest(question);

        String message;
        if (proposal.created()) {
            String proposalDetails = fetchPatchingProposalDetails(targetDomain);
            message = "Created patching workflow proposal for domain '" + targetDomain + "'. Workflow ID: "
                    + workflow.workflowId() + ".\n"
                    + "Following patches will be applied to the domain:\n"
                    + proposalDetails + "\n"
                    + "We will stop all servers, apply recommended patches, start all the servers and verify that domain is on latest patch.\n"
                    + "Reply with 'approve workflow " + workflow.workflowId() + "' (or reject/cancel).";
        } else {
            message = "A workflow is already active for domain '" + targetDomain + "'. Existing workflow ID: "
                    + proposal.conflictWorkflowId()
                    + ". Use 'status for workflow " + proposal.conflictWorkflowId() + "' or approve/reject/cancel it.";
        }

        appendConversationTurn(memoryKey, question, message);
        return new AgentResponse(message, summary, responseContext);
    }

    private String fetchPatchingProposalDetails(String targetDomain) {
        try {
            String prompt = "List recommended patches to apply for domain " + targetDomain
                    + ". Return concise patch identifiers and one-line reason for each.";
            String details = patchingAgent.analyzeRequest(prompt);
            if (isBlank(details)) {
                return "- Unable to retrieve patch list details right now.";
            }
            return details.trim();
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "Unable to fetch patch proposal details for domain " + targetDomain, ex);
            return "- Unable to retrieve patch list details right now.";
        }
    }

    private AgentResponse handleWorkflowApprovalRequest(String question,
                                                        TaskContext context,
                                                        String summary,
                                                        String memoryKey,
                                                        String normalizedQuestion) {
        ApprovalDecision decision = parseApprovalDecision(normalizedQuestion);
        List<WorkflowSummary> pending = workflowCoordinator.listPendingApproval();
        Optional<String> workflowIdFromQuestion = extractWorkflowId(question);
        String domainFromQuestion = detectMentionedDomain(question, managedDomainCacheService.getDomains());

        String resolvedWorkflowId = workflowIdFromQuestion.orElse(null);
        if (isBlank(resolvedWorkflowId) && !isBlank(domainFromQuestion)) {
            resolvedWorkflowId = workflowCoordinator.getActiveByDomain(domainFromQuestion)
                    .filter(record -> record.currentState() == WorkflowStatus.AWAITING_APPROVAL)
                    .map(WorkflowRecord::workflowId)
                    .orElse(null);
        }
        if (isBlank(resolvedWorkflowId) && context != null && !isBlank(context.lastReferencedWorkflowId())
                && pending.stream().anyMatch(item -> item.workflowId().equals(context.lastReferencedWorkflowId()))) {
            resolvedWorkflowId = context.lastReferencedWorkflowId();
        }
        if (isBlank(resolvedWorkflowId) && pending.size() == 1) {
            resolvedWorkflowId = pending.getFirst().workflowId();
        }

        if (isBlank(resolvedWorkflowId)) {
            String message = "I found no unique pending workflow to " + normalizedQuestion
                    + ". Please specify a workflowId or domain. Example: 'approve workflow <id>' or 'approve for domain payments-prod'.";
            appendConversationTurn(memoryKey, question, message);
            return new AgentResponse(message, summary, context == null ? TaskContext.empty() : context);
        }

        Optional<WorkflowRecord> updated = workflowCoordinator.applyApprovalDecision(
                resolvedWorkflowId,
                decision,
                WorkflowChannel.CHAT);

        if (updated.isEmpty()) {
            String message = "Workflow '" + resolvedWorkflowId
                    + "' is not awaiting approval (or was not found). Ask for status before retrying.";
            appendConversationTurn(memoryKey, question, message);
            return new AgentResponse(message, summary, context == null ? TaskContext.empty() : context);
        }

        WorkflowRecord workflow = updated.orElseThrow();
        approvalSemaphore.submitDecision(workflow.workflowId(), decision);
        if (decision == ApprovalDecision.APPROVE) {
            workflowCoordinator.submitApprovedWorkflowForExecution(workflow.workflowId());
        }

        TaskContext responseContext = (context == null ? TaskContext.empty() : context)
                .withLastReferencedWorkflowId(workflow.workflowId())
                .withLastUserRequest(question);
        String message = switch (workflow.currentState()) {
            case APPROVED -> "Approved workflow '" + workflow.workflowId() + "' for domain '" + workflow.domain()
                    + "'. Execution has been queued.";
            case REJECTED -> "Rejected workflow '" + workflow.workflowId() + "' for domain '" + workflow.domain() + "'.";
            case CANCELLED -> "Cancelled workflow '" + workflow.workflowId() + "' for domain '" + workflow.domain() + "'.";
            default -> "Recorded approval decision for workflow '" + workflow.workflowId() + "'.";
        };

        appendConversationTurn(memoryKey, question, message);
        return new AgentResponse(message, summary, responseContext);
    }

    private AgentResponse handleWorkflowStatusRequest(String question,
                                                      TaskContext context,
                                                      String summary,
                                                      String memoryKey) {
        Optional<String> workflowIdFromQuestion = extractWorkflowId(question);
        String domainFromQuestion = detectMentionedDomain(question, managedDomainCacheService.getDomains());

        Optional<WorkflowRecord> workflow;
        if (workflowIdFromQuestion.isPresent()) {
            workflow = workflowCoordinator.getByWorkflowId(workflowIdFromQuestion.orElseThrow());
        } else if (!isBlank(domainFromQuestion)) {
            workflow = workflowCoordinator.getLatestByDomain(domainFromQuestion);
        } else if (context != null && !isBlank(context.lastReferencedWorkflowId())) {
            workflow = workflowCoordinator.getByWorkflowId(context.lastReferencedWorkflowId());
        } else {
            String message = "Please specify which workflow status you need: provide a workflowId or domain."
                    + " Example: 'status for workflow <id>' or 'status for domain payments-prod'.";
            appendConversationTurn(memoryKey, question, message);
            return new AgentResponse(message, summary, context == null ? TaskContext.empty() : context);
        }

        if (workflow.isEmpty()) {
            String message = "I couldn't find a workflow for that reference.";
            appendConversationTurn(memoryKey, question, message);
            return new AgentResponse(message, summary, context == null ? TaskContext.empty() : context);
        }

        WorkflowRecord record = workflow.orElseThrow();
        String message = "Workflow '" + record.workflowId() + "' for domain '" + record.domain()
                + "' is in state '" + record.currentState() + "'.\n"
                + renderStepProgress(record)
                + (isBlank(record.failureReason()) ? "" : "\nFailure reason: " + record.failureReason());
        TaskContext responseContext = (context == null ? TaskContext.empty() : context)
                .withLastReferencedWorkflowId(record.workflowId())
                .withLastUserRequest(question);
        appendConversationTurn(memoryKey, question, message);
        return new AgentResponse(message, summary, responseContext);
    }

    private static String renderStepProgress(WorkflowRecord record) {
        List<String> steps = List.of("stop servers", "apply patches", "start servers", "verify patch level");
        int completedIndex = switch (record.currentState()) {
            case COMPLETED -> 3;
            case FAILED, EXECUTION_TIMED_OUT -> -1;
            case IN_EXECUTION, QUEUED, APPROVED -> -1;
            default -> -1;
        };

        String inProgress = null;
        if (record.currentState() == WorkflowStatus.IN_EXECUTION) {
            inProgress = inferInProgressStep(record);
        }

        if (record.currentState() == WorkflowStatus.COMPLETED) {
            return "Completed steps: stop servers, apply patches, start servers, verify patch level\n"
                    + "Step in progress: none\n"
                    + "Pending steps: none";
        }

        if (record.currentState() == WorkflowStatus.FAILED || record.currentState() == WorkflowStatus.EXECUTION_TIMED_OUT) {
            String failedStep = inferFailedStep(record);
            if (isBlank(failedStep)) {
                failedStep = inferInProgressStep(record);
            }
            return renderFailureProgress(steps, failedStep);
        }

        if (record.currentState() == WorkflowStatus.IN_EXECUTION
                || record.currentState() == WorkflowStatus.QUEUED
                || record.currentState() == WorkflowStatus.APPROVED) {
            return renderInExecutionProgress(steps, inProgress);
        }

        if (record.currentState() == WorkflowStatus.AWAITING_APPROVAL
                || record.currentState() == WorkflowStatus.PROPOSED
                || record.currentState() == WorkflowStatus.DRAFT) {
            return "Completed steps: none\n"
                    + "Step in progress: awaiting approval\n"
                    + "Pending steps: stop servers, apply patches, start servers, verify patch level";
        }

        StringBuilder completed = new StringBuilder("Completed steps:");
        StringBuilder pending = new StringBuilder("Pending steps:");
        for (int i = 0; i < steps.size(); i++) {
            String step = steps.get(i);
            if (completedIndex >= 0 && i <= completedIndex) {
                completed.append("\n- ").append(step);
            } else if (inProgress == null || !inProgress.equals(step)) {
                pending.append("\n- ").append(step);
            }
        }

        if (completed.toString().equals("Completed steps:")) {
            completed.append(" none");
        }
        if (pending.toString().equals("Pending steps:")) {
            pending.append(" none");
        }

        return completed + "\nStep in progress: " + (inProgress == null ? "none" : inProgress) + "\n" + pending;
    }

    private static String renderInExecutionProgress(List<String> steps, String inProgress) {
        String activeStep = isBlank(inProgress) ? "stop servers" : inProgress;

        String pending = steps.stream()
                .filter(step -> !step.equals(activeStep))
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");

        return "Completed steps: none\n"
                + "Step in progress: " + activeStep + "\n"
                + "Pending steps: " + pending;
    }

    private static String inferInProgressStep(WorkflowRecord record) {
        if (record == null || record.currentState() == null) {
            return null;
        }
        return switch (record.currentState()) {
            case IN_EXECUTION, APPROVED, QUEUED -> "stop servers";
            default -> null;
        };
    }

    private static String inferFailedStep(WorkflowRecord record) {
        if (record == null || isBlank(record.failureReason())) {
            return null;
        }
        String reason = record.failureReason().toLowerCase();
        if (reason.contains("stop servers")) {
            return "stop servers";
        }
        if (reason.contains("apply patches")) {
            return "apply patches";
        }
        if (reason.contains("start servers")) {
            return "start servers";
        }
        if (reason.contains("verify patch level") || reason.contains("verify domain")) {
            return "verify patch level";
        }
        return null;
    }

    private static String renderFailureProgress(List<String> steps, String failedStep) {
        if (isBlank(failedStep) || steps == null || steps.isEmpty()) {
            return "Completed steps: unknown\n"
                    + "Step in progress at failure: unknown\n"
                    + "Pending steps: remaining execution steps were not completed";
        }

        int failedIndex = steps.indexOf(failedStep);
        if (failedIndex < 0) {
            return "Completed steps: unknown\n"
                    + "Step in progress at failure: " + failedStep + "\n"
                    + "Pending steps: remaining execution steps were not completed";
        }

        String completed = failedIndex == 0
                ? "none"
                : String.join(", ", steps.subList(0, failedIndex));
        String pending = failedIndex == steps.size() - 1
                ? "none"
                : String.join(", ", steps.subList(failedIndex + 1, steps.size()));

        return "Completed steps: " + completed + "\n"
                + "Step in progress at failure: " + failedStep + "\n"
                + "Pending steps: " + pending;
    }

    private AgentResponse handleWorkflowListRequest(String question,
                                                    TaskContext context,
                                                    String summary,
                                                    String memoryKey,
                                                    String normalizedQuestion) {
        List<WorkflowSummary> summaries;
        String label;
        if (normalizedQuestion.contains("pending")) {
            summaries = workflowCoordinator.listPendingApproval();
            label = "pending approval";
        } else if (normalizedQuestion.contains("in execution") || normalizedQuestion.contains("executing")) {
            summaries = workflowCoordinator.listInExecution();
            label = "in execution";
        } else {
            summaries = workflowCoordinator.listAll();
            label = "all";
        }

        String message;
        if (summaries.isEmpty()) {
            message = "No " + label + " workflows found.";
        } else {
            message = """
                    %s workflows (%d):
                    %s
                    """.formatted(
                    Character.toUpperCase(label.charAt(0)) + label.substring(1),
                    summaries.size(),
                    summaries.stream()
                            .limit(10)
                            .map(item -> "- " + item.workflowId() + " | domain=" + item.domain() + " | state=" + item.currentState())
                            .reduce((a, b) -> a + "\n" + b)
                            .orElse(""))
                    .trim();
        }

        TaskContext responseContext = (context == null ? TaskContext.empty() : context)
                .withActiveWorkflowIds(summaries.stream().map(WorkflowSummary::workflowId).limit(10).toList())
                .withLastUserRequest(question);
        appendConversationTurn(memoryKey, question, message);
        return new AgentResponse(message, summary, responseContext);
    }

    private static boolean looksLikeWorkflowProposalRequest(String normalizedQuestion) {
        return normalizedQuestion.contains("patch")
                && (normalizedQuestion.contains("apply") || normalizedQuestion.contains("recommended"));
    }

    private static boolean looksLikeWorkflowApprovalRequest(String normalizedQuestion) {
        return normalizedQuestion.startsWith("approve")
                || normalizedQuestion.startsWith("reject")
                || normalizedQuestion.startsWith("cancel workflow")
                || normalizedQuestion.startsWith("cancel approval");
    }

    private static boolean looksLikeWorkflowStatusRequest(String normalizedQuestion) {
        return normalizedQuestion.contains("status") && normalizedQuestion.contains("workflow")
                || normalizedQuestion.startsWith("status for domain");
    }

    private static boolean looksLikeWorkflowListRequest(String normalizedQuestion) {
        return normalizedQuestion.contains("list workflows")
                || normalizedQuestion.contains("show workflows")
                || normalizedQuestion.contains("pending workflows")
                || normalizedQuestion.contains("pending approval workflows")
                || normalizedQuestion.contains("workflows in execution")
                || normalizedQuestion.contains("in-execution workflows");
    }

    private static ApprovalDecision parseApprovalDecision(String normalizedQuestion) {
        if (normalizedQuestion.startsWith("approve")) {
            return ApprovalDecision.APPROVE;
        }
        if (normalizedQuestion.startsWith("reject")) {
            return ApprovalDecision.REJECT;
        }
        return ApprovalDecision.CANCEL;
    }

    private static Optional<String> extractWorkflowId(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = Pattern.compile("(?i)\\bworkflow\\s+([A-Za-z0-9-]{6,})\\b").matcher(question);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        Matcher uuidMatcher = Pattern.compile("(?i)\\b([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\b").matcher(question);
        if (uuidMatcher.find()) {
            return Optional.of(uuidMatcher.group(1));
        }
        return Optional.empty();
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
                "TaskContext stage={0} domain={1}, servers={2}, hosts={3}, memorySummaryLen={4}",
                new Object[]{
                        stage,
                        safe(context.targetDomain()),
                        safe(context.targetServers()),
                        safe(context.targetHosts()),
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
                context.activeWorkflowIds(),
                context.lastReferencedWorkflowId(),
                context.failureReason());
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

    private static java.util.List<String> getStringList(JsonObject object, String key) {
        if (object == null || key == null || !object.containsKey(key) || object.isNull(key)) {
            return null;
        }
        java.util.List<String> values = object.getJsonArray(key).stream()
                .filter(item -> item != null && item.getValueType() == JsonValue.ValueType.STRING)
                .map(item -> ((JsonString) item).getString())
                .filter(value -> value != null && !value.isBlank())
                .toList();
        return values.isEmpty() ? null : values;
    }

    private static java.util.List<String> firstNonEmptyList(java.util.List<String> preferred,
                                                            java.util.List<String> fallback) {
        return preferred != null && !preferred.isEmpty() ? preferred : fallback;
    }
}
