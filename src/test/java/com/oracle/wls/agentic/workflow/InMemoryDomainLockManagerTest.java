package com.oracle.wls.agentic.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryDomainLockManagerTest {

    @Test
    void lockOwnershipSemanticsAreOwnerAware() {
        InMemoryDomainLockManager lockManager = new InMemoryDomainLockManager();

        assertTrue(lockManager.acquire("domainA", "workflow-1"));
        assertFalse(lockManager.acquire("domainA", "workflow-2"));
        assertTrue(lockManager.isLocked("domainA"));
        assertEquals("workflow-1", lockManager.lockOwner("domainA"));

        assertFalse(lockManager.release("domainA", "workflow-2"));
        assertTrue(lockManager.isLocked("domainA"));

        assertTrue(lockManager.release("domainA", "workflow-1"));
        assertFalse(lockManager.isLocked("domainA"));
        assertNull(lockManager.lockOwner("domainA"));
    }
}