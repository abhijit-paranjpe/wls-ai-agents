package com.oracle.wls.agentic.memory;

import com.oracle.wls.agentic.ai.ManagedDomainsAgent;
import io.helidon.config.Config;
import io.helidon.service.registry.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service.Singleton
public class ManagedDomainCacheService {

    private static final Logger LOGGER = Logger.getLogger(ManagedDomainCacheService.class.getName());
    private static final Pattern JSON_ARRAY_STRING_PATTERN = Pattern.compile("\\\"([^\\\"]+)\\\"");

    private final ManagedDomainsAgent managedDomainsAgent;
    private final AtomicReference<List<String>> cachedDomains = new AtomicReference<>(List.of());
    private final long refreshIntervalMs;
    private volatile long lastRefreshEpochMs;

    @Service.Inject
    public ManagedDomainCacheService(ManagedDomainsAgent managedDomainsAgent) {
        this.managedDomainsAgent = managedDomainsAgent;
        this.refreshIntervalMs = Config.global()
                .get("managed-domains.cache-refresh-seconds")
                .asLong()
                .orElse(300L) * 1000L;
        refreshNow();
    }

    public List<String> getDomains() {
        refreshIfStale();
        return cachedDomains.get();
    }

    public void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshEpochMs >= refreshIntervalMs) {
            refreshNow();
        }
    }

    public synchronized void refreshNow() {
        try {
            String response = managedDomainsAgent.listManagedDomains();
            List<String> parsed = parseDomainNames(response);
            if (!parsed.isEmpty()) {
                cachedDomains.set(List.copyOf(parsed));
                LOGGER.log(Level.INFO, "Managed domain cache refreshed. domains={0}", parsed);
            } else {
                LOGGER.log(Level.WARNING,
                        "Managed domain cache refresh returned no domains; retaining previous cache. rawResponse={0}",
                        response);
            }
            lastRefreshEpochMs = System.currentTimeMillis();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Managed domain cache refresh failed; retaining previous cache", e);
            lastRefreshEpochMs = System.currentTimeMillis();
        }
    }

    private static List<String> parseDomainNames(String response) {
        if (response == null || response.isBlank()) {
            return Collections.emptyList();
        }

        // Prefer strict JSON-array-like output with quoted strings.
        List<String> names = new ArrayList<>();
        Matcher matcher = JSON_ARRAY_STRING_PATTERN.matcher(response);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (name != null && !name.isBlank()) {
                names.add(name.trim());
            }
        }

        if (!names.isEmpty()) {
            return deduplicate(names);
        }

        // Fallback: comma/newline separated plain text.
        String[] parts = response.split("[\\n,]");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (!value.isBlank() && !value.startsWith("[") && !value.endsWith("]")) {
                names.add(value.replace("\"", ""));
            }
        }
        return deduplicate(names);
    }

    private static List<String> deduplicate(List<String> names) {
        List<String> deduped = new ArrayList<>();
        for (String name : names) {
            boolean exists = deduped.stream().anyMatch(existing -> existing.equalsIgnoreCase(name));
            if (!exists) {
                deduped.add(name);
            }
        }
        return deduped;
    }
}
