package com.example.wls.agentic.rest;

import com.example.wls.agentic.ai.WebLogicAgent;
import com.example.wls.agentic.dto.AgentResponse;
import com.example.wls.agentic.dto.TaskContext;
import com.example.wls.agentic.dto.TaskContexts;
import com.example.wls.agentic.memory.ConversationMemoryService;
import com.example.wls.agentic.memory.ManagedDomainCacheService;
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

    private final WebLogicAgent agent;
    private final ConversationMemoryService conversationMemoryService;
    private final ManagedDomainCacheService managedDomainCacheService;

    @Service.Inject
    public ChatBotEndpoint(WebLogicAgent agent,
                           ConversationMemoryService conversationMemoryService,
                           ManagedDomainCacheService managedDomainCacheService) {
        this.agent = agent;
        this.conversationMemoryService = conversationMemoryService;
        this.managedDomainCacheService = managedDomainCacheService;
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
                    context.lastAssistantQuestion());
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

        String followUpAwareQuestion = maybeRewritePendingFollowUpQuestion(question, context, managedDomains);
        String contextualizedQuestion = maybeApplyImplicitDomainContext(followUpAwareQuestion, context, managedDomains);

        String persistedSummary = loadPersistedSummary(memoryKey);
        String summary = firstNonBlank(msg.summary(), context.memorySummary(), persistedSummary, "");
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
                    TaskContexts.toPromptContext(compactedContext),
                    compactedContext);
            TaskContext finalResponseContext = finalizeResponseTaskContext(
                    context,
                    response.taskContext(),
                    question,
                    response.message(),
                    managedDomains);
            AgentResponse finalResponse = new AgentResponse(response.message(), response.summary(), finalResponseContext);
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
                getString(taskContextObject, "lastAssistantQuestion"));
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
                || obj.containsKey("lastAssistantQuestion");
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

    private void savePersistedTaskContext(TaskContext taskContext) {
        if (taskContext == null) {
            return;
        }
        conversationMemoryService.store().saveTaskContext(taskContext.conversationId(), taskContext);
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
                firstNonBlank(incoming.lastAssistantQuestion(), persisted.lastAssistantQuestion()));
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
                base.lastAssistantQuestion());
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
            return TaskContexts.clearPendingFollowUp(merged);
        }

        String activeIntent = firstNonBlank(merged.intent(), merged.pendingIntent());
        if (!shouldAwaitFollowUp(activeIntent, assistantMessage)) {
            return TaskContexts.clearPendingFollowUp(merged);
        }

        return merged.withPendingFollowUp(activeIntent, true, extractAssistantFollowUpPrompt(assistantMessage));
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
                truncate(context.lastAssistantQuestion(), MAX_CONSTRAINTS_CHARS));
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
