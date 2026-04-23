package com.example.wls.agentic.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TaskContextActiveWorkflowIdTest {

    @Test
    void withActiveWorkflowIdUpdatesOnlyWorkflowField() {
        TaskContext context = TaskContext.empty().withConversationId("conv-1").withIntent("PATCHING");

        TaskContext updated = context.withActiveWorkflowId("wf-999");

        assertEquals("wf-999", updated.activeWorkflowId());
        assertEquals("conv-1", updated.conversationId());
        assertEquals("PATCHING", updated.intent());
        assertNull(context.activeWorkflowId());
    }
}
