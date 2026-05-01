package com.oracle.wls.agentic.workflow;

import io.helidon.service.registry.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service.Singleton
public class InMemoryDomainLockManager implements DomainLockManager {

    private final Map<String, String> lockOwnersByDomain = new ConcurrentHashMap<>();

    @Override
    public boolean acquire(String domain, String ownerId) {
        validate(domain, ownerId);
        return lockOwnersByDomain.putIfAbsent(domain, ownerId) == null;
    }

    @Override
    public boolean release(String domain, String ownerId) {
        validate(domain, ownerId);
        return lockOwnersByDomain.remove(domain, ownerId);
    }

    @Override
    public boolean isLocked(String domain) {
        if (domain == null || domain.isBlank()) {
            return false;
        }
        return lockOwnersByDomain.containsKey(domain);
    }

    @Override
    public String lockOwner(String domain) {
        if (domain == null || domain.isBlank()) {
            return null;
        }
        return lockOwnersByDomain.get(domain);
    }

    private static void validate(String domain, String ownerId) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain must not be blank");
        }
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId must not be blank");
        }
    }
}