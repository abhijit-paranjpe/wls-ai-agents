package com.example.wls.agentic.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryWorkflowStateStoreTest {

    @Test
    void storesDistinctWorkflowsPerConversationDomainAndOperation() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();

        WorkflowState domainAApply = WorkflowState.create(
                "conv-1",
                "user-1",
                "PATCHING",
                "APPLYING_PATCHES",
                "IN_PROGRESS",
                "domain-a",
                null,
                null,
                "PATCH_APPLY",
                null,
                "apply patches to domain-a",
                null,
                null,
                false);

        WorkflowState domainBRollback = WorkflowState.create(
                "conv-1",
                "user-1",
                "PATCHING",
                "VERIFYING_STATUS",
                "IN_PROGRESS",
                "domain-b",
                null,
                null,
                "PATCH_ROLLBACK",
                null,
                "rollback patches for domain-b",
                null,
                null,
                false);

        store.save(domainAApply);
        store.save(domainBRollback);

        WorkflowState loadedA = store.loadByConversationIdAndDomainAndOperation("conv-1", "domain-a", "PATCH_APPLY").orElseThrow();
        WorkflowState loadedB = store.loadByConversationIdAndDomainAndOperation("conv-1", "domain-b", "PATCH_ROLLBACK").orElseThrow();

        assertEquals("domain-a", loadedA.targetDomain());
        assertEquals("PATCH_APPLY", loadedA.requestedOperation());

        assertEquals("domain-b", loadedB.targetDomain());
        assertEquals("PATCH_ROLLBACK", loadedB.requestedOperation());
    }

    @Test
    void clearRemovesOnlyMatchingConversationDomainAndOperation() {
        InMemoryWorkflowStateStore store = new InMemoryWorkflowStateStore();

        WorkflowState apply = WorkflowState.create(
                "conv-2",
                "user-1",
                "PATCHING",
                "APPLYING_PATCHES",
                "IN_PROGRESS",
                "domain-a",
                null,
                null,
                "PATCH_APPLY",
                null,
                "apply",
                null,
                null,
                false);

        WorkflowState rollback = WorkflowState.create(
                "conv-2",
                "user-1",
                "PATCHING",
                "VERIFYING_STATUS",
                "IN_PROGRESS",
                "domain-a",
                null,
                null,
                "PATCH_ROLLBACK",
                null,
                "rollback",
                null,
                null,
                false);

        store.save(apply);
        store.save(rollback);

        store.clear("conv-2", "domain-a", "PATCH_APPLY");

        assertTrue(store.loadByConversationIdAndDomainAndOperation("conv-2", "domain-a", "PATCH_APPLY").isEmpty());
        assertTrue(store.loadByConversationIdAndDomainAndOperation("conv-2", "domain-a", "PATCH_ROLLBACK").isPresent());
    }
}
