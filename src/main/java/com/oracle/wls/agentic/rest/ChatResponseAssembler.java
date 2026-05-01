package com.oracle.wls.agentic.rest;

import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import java.io.StringReader;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ChatResponseAssembler {

    private static final Pattern TYPE_PATTERN = Pattern.compile("(?i)\\btype\\s*[:=]?\\s*([A-Za-z0-9._-]+)\\b");
    private static final Pattern WORKFLOW_ACTION_HINT_PATTERN = Pattern.compile(
            "(?i)\\b(?:approve|reject|cancel)\\s+workflow\\s+[A-Za-z0-9-]{6,}\\b");
    private static final Pattern HOST_IN_MESSAGE_PATTERN = Pattern.compile("(?i)\\bon\\s+host\\s+([A-Za-z0-9._-]+)");
    private static final Pattern PID_IN_MESSAGE_PATTERN = Pattern.compile("(?i)\\bpid\\s*(?:[:=#]|is|=)?\\s*(\\d+)");

    private ChatResponseAssembler() {
    }

    static String normalizeAsyncTrackingResult(String rawResult, String pid, String host) {
        String text = nonEmpty(rawResult, "").trim();
        if (text.isBlank()) {
            return rawResult;
        }

        String safePid = sanitizePid(pid);
        String safeHost = sanitizeHost(host);

        int pidMentions = countOccurrencesIgnoreCase(text, safePid);
        if (pidMentions <= 1) {
            return text;
        }

        String status = "running";
        String lower = text.toLowerCase();
        if (lower.contains("completed")) {
            status = "completed";
        } else if (lower.contains("failed")) {
            status = "failed";
        } else if (lower.contains("not found")) {
            status = "not found";
        } else if (lower.contains("started")) {
            status = "started";
        }

        String type = null;
        Matcher typeMatcher = TYPE_PATTERN.matcher(text);
        if (typeMatcher.find()) {
            type = typeMatcher.group(1);
        }

        StringBuilder normalized = new StringBuilder("Async job status on host ")
                .append(safeHost)
                .append(" for PID ")
                .append(safePid)
                .append(": ")
                .append(status);
        if (!isBlank(type)) {
            normalized.append(" (type: ").append(type).append(")");
        }
        normalized.append('.');
        return normalized.toString();
    }

    static String normalizeNonWorkflowOperationMessage(String rawMessage) {
        if (isBlank(rawMessage)) {
            return rawMessage;
        }
        String trimmed = rawMessage.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return rawMessage;
        }

        try (JsonReader reader = jakarta.json.Json.createReader(new StringReader(trimmed))) {
            JsonObject object = reader.readObject();

            String patchStatusMessage = normalizePatchStatusMessage(object);
            if (!isBlank(patchStatusMessage)) {
                return patchStatusMessage;
            }

            String status = getString(object, "status");
            String host = getString(object, "host");
            String pid = getString(object, "pid");
            String operation = getString(object, "operation");
            String message = getString(object, "message");

            if (isBlank(host) || isBlank(pid)) {
                JsonObject hostPids = object.getJsonObject("hostPids");
                if (hostPids != null && !hostPids.isEmpty()) {
                    String inferredHost = null;
                    String inferredPid = null;
                    for (String key : hostPids.keySet()) {
                        if (isBlank(key) || hostPids.isNull(key)) {
                            continue;
                        }
                        String value = hostPids.getString(key, "");
                        if (isBlank(value)) {
                            continue;
                        }
                        inferredHost = key;
                        inferredPid = value;
                        break;
                    }
                    if (isBlank(host) && !isBlank(inferredHost)) {
                        host = inferredHost;
                    }
                    if (isBlank(pid) && !isBlank(inferredPid)) {
                        pid = inferredPid;
                    }
                }
            }

            if (isBlank(host)) {
                host = extractHostFromMessage(message);
            }
            if (isBlank(pid)) {
                pid = extractPidFromMessage(message);
            }

            if (isBlank(status) && isBlank(host) && isBlank(pid) && isBlank(operation)) {
                return rawMessage;
            }

            if (isRuntimeValidationMessage(message)) {
                return message.trim();
            }

            if (isBlank(status) || isBlank(host) || isBlank(pid) || isBlank(operation)) {
                return buildMissingRuntimeFieldsMessage(status, host, pid, operation, message);
            }

            String opLabel = humanizeOperation(operation);
            if (isBlank(opLabel)) {
                opLabel = "Operation";
            }
            String statusWord = status.toLowerCase();

            StringBuilder normalized = new StringBuilder(opLabel)
                    .append(" is ")
                    .append(statusWord);
            if (!isBlank(host)) {
                normalized.append(" on host ").append(host);
            }
            if (!isBlank(pid)) {
                normalized.append(" (PID ").append(pid).append(")");
            }
            normalized.append('.');

            if (!isBlank(message)
                    && !message.equalsIgnoreCase(statusWord)
                    && !message.trim().equalsIgnoreCase(trimmed)
                    && !isLowValueRuntimeMessage(message)
                    && !isRedundantRuntimeDetailMessage(message, operation, statusWord, host, pid)) {
                normalized.append(' ').append(message.trim());
            }
            return normalized.toString();
        } catch (RuntimeException ex) {
            return rawMessage;
        }
    }

    private static String normalizePatchStatusMessage(JsonObject object) {
        if (object == null) {
            return null;
        }

        boolean hasPatchStatusShape = object.containsKey("isDomainOnLatestPatches")
                || object.containsKey("applicablePatches")
                || object.containsKey("results");
        if (!hasPatchStatusShape) {
            return null;
        }

        String domain = firstNonBlank(getString(object, "domainName"), getString(object, "domain"), "target domain");
        boolean onLatest = object.containsKey("isDomainOnLatestPatches")
                && !object.isNull("isDomainOnLatestPatches")
                && object.getBoolean("isDomainOnLatestPatches", false);

        JsonValue patchArray = object.get("applicablePatches");
        if ((patchArray == null || patchArray.getValueType() != JsonValue.ValueType.ARRAY)
                && object.containsKey("results")
                && !object.isNull("results")
                && object.get("results").getValueType() == JsonValue.ValueType.ARRAY
                && !object.getJsonArray("results").isEmpty()) {
            JsonValue firstResult = object.getJsonArray("results").get(0);
            if (firstResult != null
                    && firstResult.getValueType() == JsonValue.ValueType.OBJECT
                    && firstResult.asJsonObject().containsKey("applicablePatches")) {
                patchArray = firstResult.asJsonObject().get("applicablePatches");
            }
        }

        if (onLatest) {
            return "Domain '" + domain + "' is already on the latest patches.";
        }

        if (patchArray != null && patchArray.getValueType() == JsonValue.ValueType.ARRAY && !patchArray.asJsonArray().isEmpty()) {
            String details = patchArray.asJsonArray().stream()
                    .filter(item -> item != null && item.getValueType() == JsonValue.ValueType.OBJECT)
                    .map(item -> item.asJsonObject())
                    .map(item -> {
                        String id = firstNonBlank(getString(item, "id"), getString(item, "patch_id"));
                        String reason = getString(item, "reason");
                        if (isBlank(id) && isBlank(reason)) {
                            return null;
                        }
                        if (isBlank(id)) {
                            return "- " + reason;
                        }
                        if (isBlank(reason)) {
                            return "- Patch " + id;
                        }
                        return "- Patch " + id + ": " + reason;
                    })
                    .filter(line -> !isBlank(line))
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse(null);

            if (!isBlank(details)) {
                return "Domain '" + domain + "' is not on the latest patches. Applicable patches:\n" + details;
            }
        }

        return "Domain '" + domain + "' is not on the latest patches.";
    }

    static String formatPatchingProposalDetails(String rawDetails) {
        String trimmed = stripWorkflowActionHints(rawDetails == null ? "" : rawDetails.trim());
        if (trimmed.isBlank()) {
            return "- Unable to retrieve patch list details right now.";
        }

        String jsonCandidate = trimmed;
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            jsonCandidate = trimmed.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .trim();
        }

        if (!(jsonCandidate.startsWith("{") && jsonCandidate.endsWith("}"))) {
            return stripWorkflowActionHints(trimmed);
        }

        try (JsonReader reader = jakarta.json.Json.createReader(new StringReader(jsonCandidate))) {
            JsonObject object = reader.readObject();
            String patchArrayField = null;
            if (object.containsKey("recommendedPatches")
                    && !object.isNull("recommendedPatches")
                    && object.get("recommendedPatches").getValueType() == JsonValue.ValueType.ARRAY) {
                patchArrayField = "recommendedPatches";
            } else if (object.containsKey("applicablePatches")
                    && !object.isNull("applicablePatches")
                    && object.get("applicablePatches").getValueType() == JsonValue.ValueType.ARRAY) {
                patchArrayField = "applicablePatches";
            }

            if (isBlank(patchArrayField)) {
                return stripWorkflowActionHints(trimmed);
            }

            var patches = object.getJsonArray(patchArrayField);
            if (patches == null || patches.isEmpty()) {
                return "- No recommended patches reported.";
            }

            return patches.stream()
                    .filter(item -> item != null && item.getValueType() == JsonValue.ValueType.OBJECT)
                    .map(item -> item.asJsonObject())
                    .map(item -> {
                        String id = firstNonBlank(getString(item, "id"), getString(item, "patch_id"));
                        String reason = getString(item, "reason");
                        if (isBlank(id) && isBlank(reason)) {
                            return null;
                        }
                        if (isBlank(id)) {
                            return "- " + reason;
                        }
                        if (isBlank(reason)) {
                            return "- Patch " + id;
                        }
                        return "- Patch " + id + ": " + reason;
                    })
                    .filter(line -> line != null && !line.isBlank())
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("- No recommended patches reported.");
        } catch (RuntimeException ex) {
            return stripWorkflowActionHints(trimmed);
        }
    }

    private static String stripWorkflowActionHints(String value) {
        if (isBlank(value)) {
            return value;
        }
        String stripped = WORKFLOW_ACTION_HINT_PATTERN.matcher(value).replaceAll("");
        stripped = stripped.replaceAll("(?m)^[\\s|,:-]+$", "");
        stripped = stripped.replaceAll("[ \\t]{2,}", " ").trim();
        stripped = stripped.replaceAll("\\n{3,}", "\\n\\n");
        return stripped;
    }

    private static boolean isLowValueRuntimeMessage(String message) {
        if (isBlank(message)) {
            return true;
        }
        String lower = message.trim().toLowerCase();
        return "start initiated".equals(lower)
                || "stop initiated".equals(lower)
                || "operation initiated".equals(lower)
                || "initiated".equals(lower)
                || "started".equals(lower);
    }

    private static boolean isRuntimeValidationMessage(String message) {
        if (isBlank(message)) {
            return false;
        }
        String lower = message.trim().toLowerCase();
        return lower.contains("domain name is required")
                || lower.contains("please provide the domain name");
    }

    private static boolean isRedundantRuntimeDetailMessage(String message,
                                                           String operation,
                                                           String status,
                                                           String host,
                                                           String pid) {
        if (isBlank(message)) {
            return true;
        }
        String lower = message.toLowerCase();
        boolean hasHost = !isBlank(host) && lower.contains(host.toLowerCase());
        boolean hasPid = !isBlank(pid) && lower.contains(pid.toLowerCase());
        String opHuman = humanizeOperation(operation);
        boolean hasOperation = !isBlank(opHuman) && lower.contains(opHuman.toLowerCase());
        boolean hasStatus = !isBlank(status) && lower.contains(status.toLowerCase());
        boolean isGenericInitiation = lower.contains("initiated") || lower.contains("started") || lower.contains("in progress");

        return (hasHost && hasPid)
                || (hasOperation && hasHost && hasPid)
                || (hasOperation && hasStatus && hasHost)
                || (hasOperation && hasStatus && hasPid)
                || (isGenericInitiation && (hasOperation || hasStatus));
    }

    private static String buildMissingRuntimeFieldsMessage(String status,
                                                           String host,
                                                           String pid,
                                                           String operation,
                                                           String message) {
        String missingFields = String.join(", ", Stream.of(
                        isBlank(operation) ? "operation" : null,
                        isBlank(status) ? "status" : null,
                        isBlank(host) ? "host" : null,
                        isBlank(pid) ? "pid" : null)
                .filter(value -> value != null && !value.isBlank())
                .toList());

        StringBuilder response = new StringBuilder(
                "Runtime operation response is incomplete (missing: "
                        + missingFields
                        + "). Please retry.");

        if (!isBlank(operation) || !isBlank(status) || !isBlank(host) || !isBlank(pid)) {
            response.append(" Partial details:");
            if (!isBlank(operation)) {
                response.append(" operation=").append(operation);
            }
            if (!isBlank(status)) {
                response.append(" status=").append(status);
            }
            if (!isBlank(host)) {
                response.append(" host=").append(host);
            }
            if (!isBlank(pid)) {
                response.append(" pid=").append(pid);
            }
            response.append('.');
        }

        if (!isBlank(message)) {
            response.append(' ').append(message.trim());
        }
        return response.toString();
    }

    private static String humanizeOperation(String operation) {
        if (isBlank(operation)) {
            return null;
        }
        String normalized = operation.trim().replace('-', ' ').replace('_', ' ');
        if (normalized.isBlank()) {
            return null;
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static int countOccurrencesIgnoreCase(String text, String token) {
        if (isBlank(text) || isBlank(token)) {
            return 0;
        }
        String haystack = text.toLowerCase();
        String needle = token.toLowerCase();
        int from = 0;
        int count = 0;
        while (from >= 0) {
            int index = haystack.indexOf(needle, from);
            if (index < 0) {
                break;
            }
            count++;
            from = index + needle.length();
        }
        return count;
    }

    private static String sanitizePid(String pid) {
        if (isBlank(pid)) {
            return "unknown";
        }
        Matcher digits = Pattern.compile("(\\d+)").matcher(pid);
        if (digits.find()) {
            return digits.group(1);
        }
        return pid.replaceAll("[^A-Za-z0-9._-]", "");
    }

    private static String sanitizeHost(String host) {
        if (isBlank(host)) {
            return "unknown";
        }
        return host.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9._-]+$", "");
    }

    private static String extractHostFromMessage(String message) {
        if (isBlank(message)) {
            return null;
        }
        Matcher matcher = HOST_IN_MESSAGE_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String extractPidFromMessage(String message) {
        if (isBlank(message)) {
            return null;
        }
        Matcher matcher = PID_IN_MESSAGE_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String getString(JsonObject obj, String key) {
        return obj != null && obj.containsKey(key) && !obj.isNull(key) ? obj.getString(key, "") : null;
    }

    private static String nonEmpty(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
