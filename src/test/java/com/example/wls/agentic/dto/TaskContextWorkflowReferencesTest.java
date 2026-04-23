package com.example.wls.agentic.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TaskContextWorkflowReferencesTest {

    @Test
    void workflowReferenceMutatorsUpdateExpectedFields() {
        TaskContext context = TaskContext.empty()
                .withConversationId("conv-1")
                .withIntent("PATCHING");

        TaskContext withActiveWorkflows = context.withActiveWorkflowIds(List.of("wf-101", "wf-202"));
        TaskContext updated = withActiveWorkflows.withLastReferencedWorkflowId("wf-202");

        assertEquals(List.of("wf-101", "wf-202"), updated.activeWorkflowIds());
        assertEquals("wf-202", updated.lastReferencedWorkflowId());
        assertEquals("conv-1", updated.conversationId());
        assertEquals("PATCHING", updated.intent());
        assertNull(context.activeWorkflowIds());
        assertNull(context.lastReferencedWorkflowId());
    }
}
