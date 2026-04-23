package com.example.wls.agentic.workflow;

public interface DomainLockManager {

    boolean acquire(String domain, String ownerId);

    boolean release(String domain, String ownerId);

    boolean isLocked(String domain);

    String lockOwner(String domain);
}
