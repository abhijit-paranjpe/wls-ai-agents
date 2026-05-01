package com.example.wls.agentic.ai;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.helidon.integrations.langchain4j.Ai;
import io.helidon.service.registry.Service;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

@Service.Singleton
@Ai.Tool
public class BeaErrorLookupTool {

    private static final Pattern BEA_CODE_PATTERN = Pattern.compile("(BEA-\\d{6})", Pattern.CASE_INSENSITIVE);

    private static final String V12214 = "12.2.1.4";
    private static final String V1412 = "14.1.2";

    private static final String FILE_12214 = "diagnostics/bea_error_codes_12_2_1_4.json";
    private static final String FILE_1412 = "diagnostics/bea_error_codes_14_1_2.json";

    private volatile Map<String, JsonObject> v12214Catalog;
    private volatile Map<String, JsonObject> v1412Catalog;

    @Tool("Look up a WebLogic BEA error code from local catalogs. Optional version supports 12.2.1.4 or 14.1.2.")
    public String lookupBeaErrorCode(@P("BEA code like BEA-000101") String code,
            @P("Optional WebLogic version like 12.2.1.4 or 14.1.2") String version) {

        String normalizedCode = normalizeCode(code);
        if (normalizedCode == null) {
            return "ERROR: invalid BEA code. Expected format like BEA-000101.";
        }

        String normalizedVersion = normalizeVersion(version);
        if (normalizedVersion != null) {
            JsonObject entry = findInVersion(normalizedCode, normalizedVersion);
            if (entry == null) {
                return "NOT_FOUND\ncode=" + normalizedCode + "\nsearchedVersions=" + normalizedVersion;
            }
            return formatHit(normalizedCode, normalizedVersion, entry);
        }

        List<String> matchedVersions = new ArrayList<>();
        JsonObject entry12214 = findInVersion(normalizedCode, V12214);
        if (entry12214 != null) {
            matchedVersions.add(V12214);
        }
        JsonObject entry1412 = findInVersion(normalizedCode, V1412);
        if (entry1412 != null) {
            matchedVersions.add(V1412);
        }

        if (matchedVersions.isEmpty()) {
            return "NOT_FOUND\ncode=" + normalizedCode + "\nsearchedVersions=" + V12214 + "," + V1412;
        }

        String selectedVersion = matchedVersions.get(0);
        JsonObject selected = V12214.equals(selectedVersion) ? entry12214 : entry1412;
        return formatHit(normalizedCode, selectedVersion, selected) + "\nmatchedVersions=" + String.join(",", matchedVersions);
    }

    private JsonObject findInVersion(String code, String version) {
        if (V12214.equals(version)) {
            return get12214Catalog().get(code);
        }
        if (V1412.equals(version)) {
            return get1412Catalog().get(code);
        }
        return null;
    }

    private Map<String, JsonObject> get12214Catalog() {
        Map<String, JsonObject> local = v12214Catalog;
        if (local == null) {
            synchronized (this) {
                local = v12214Catalog;
                if (local == null) {
                    local = loadCatalog(FILE_12214);
                    v12214Catalog = local;
                }
            }
        }
        return local;
    }

    private Map<String, JsonObject> get1412Catalog() {
        Map<String, JsonObject> local = v1412Catalog;
        if (local == null) {
            synchronized (this) {
                local = v1412Catalog;
                if (local == null) {
                    local = loadCatalog(FILE_1412);
                    v1412Catalog = local;
                }
            }
        }
        return local;
    }

    private static Map<String, JsonObject> loadCatalog(String resourcePath) {
        try (InputStream in = BeaErrorLookupTool.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return Map.of();
            }
            try (JsonReader reader = Json.createReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                JsonArray array = reader.readArray();
                Map<String, JsonObject> result = new LinkedHashMap<>();
                for (JsonValue value : array) {
                    if (value.getValueType() != JsonValue.ValueType.OBJECT) {
                        continue;
                    }
                    JsonObject obj = value.asJsonObject();
                    String code = normalizeCode(obj.getString("code", null));
                    if (code != null) {
                        result.put(code, obj);
                    }
                }
                return result;
            }
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String normalizeCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = BEA_CODE_PATTERN.matcher(raw.trim().toUpperCase(Locale.ROOT));
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String normalizeVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("12.2.1.4")) {
            return V12214;
        }
        if (trimmed.startsWith("14.1.2") || trimmed.startsWith("14.1.1.2")) {
            return V1412;
        }
        return null;
    }

    private static String formatHit(String code, String version, JsonObject entry) {
        return "OK\n"
                + "code=" + code + "\n"
                + "catalogVersion=" + version + "\n"
                + "message=" + entry.getString("message", "") + "\n"
                + "category=" + entry.getString("category", "") + "\n"
                + "cause=" + entry.getString("cause", "") + "\n"
                + "action=" + entry.getString("action", "");
    }
}