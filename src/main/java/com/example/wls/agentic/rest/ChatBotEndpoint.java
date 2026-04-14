package com.example.wls.agentic.rest;

import com.example.wls.agentic.ai.WebLogicAgent;
import com.example.wls.agentic.dto.AgentResponse;
import com.example.wls.agentic.dto.TaskContext;
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
import java.util.Map;
import java.util.Set;
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

    @Service.Inject
    public ChatBotEndpoint(WebLogicAgent agent) {
        this.agent = agent;
    }

    @Http.POST
    @Http.Path("/chat")
    @Http.Produces(APPLICATION_JSON_VALUE)
    public AgentResponse chatWithAssistant(@Http.Entity String rawBody) {
        AgentResponse msg = parseIncomingBody(rawBody);
        logContext("parsed-request", msg.taskContext());

        TaskContext incomingContext = msg.taskContext() == null ? TaskContext.empty() : msg.taskContext();

        TaskContext context = incomingContext.withMemorySummary(
                nonEmpty(incomingContext.memorySummary(), msg.summary()));

        String question = nonEmpty(msg.message(), "");
        context = applyDomainContextFromQuestion(question, context);
        context = applyServerContextFromQuestion(question, context);
        context = applyHostContextFromQuestion(question, context);
        logContext("after-enrichment", context);

        String contextualizedQuestion = maybeApplyImplicitDomainContext(question, context);

        String summary = nonEmpty(msg.summary(), context.memorySummary());
        TaskContext compactedContext = compactContext(context);
        String compactedSummary = truncate(summary, MAX_MEMORY_SUMMARY_CHARS);

        try {
            AgentResponse response = agent.chat(
                    contextualizedQuestion,
                    compactedSummary,
                    compactedContext.toPromptContext(),
                    compactedContext);
            logContext("agent-response", response.taskContext());
            return response;
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
                getString(taskContextObject, "memorySummary"));
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
                || obj.containsKey("confirmTargetOnImplicitReuse");
    }

    private static String nonEmpty(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static TaskContext applyDomainContextFromQuestion(String question, TaskContext context) {
        if (!shouldInferDomainFromQuestion(question)) {
            return context;
        }

        String detectedDomain = detectMentionedDomain(question);
        if (detectedDomain == null || detectedDomain.isBlank()) {
            return context;
        }

        if (!isBlank(context.targetDomain()) && !context.targetDomain().equalsIgnoreCase(detectedDomain)) {
            return flushContextForDomainChange(context, detectedDomain);
        }

        return context.withTargetDomain(detectedDomain);
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
                null);
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

    private static String maybeApplyImplicitDomainContext(String question, TaskContext context) {
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

        if (detectMentionedDomain(question) != null || isBlank(context.targetDomain())) {
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

    private static String detectMentionedDomain(String question) {
        if (question == null) {
            return null;
        }

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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean asksToOperateOnGenericDomain(String question) {
        String lower = question.toLowerCase();
        boolean mentionsGenericDomain = lower.contains("the domain") || lower.matches(".*\\bdomain\\b.*");
        boolean hasOperationVerb = lower.contains("start")
                || lower.contains("stop")
                || lower.contains("restart")
                || lower.contains("shutdown")
                || lower.contains("graceful");
        return mentionsGenericDomain && hasOperationVerb && detectMentionedDomain(question) == null;
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
                truncate(context.memorySummary(), MAX_MEMORY_SUMMARY_CHARS));
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
