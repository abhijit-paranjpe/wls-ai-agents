package com.example.wls.agentic.workflow;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowApprovalSemaphoreTest {

    @Test
    void awaitDecisionResumesOnSubmittedDecision() throws Exception {
        WorkflowApprovalSemaphore semaphore = new WorkflowApprovalSemaphore();

        CompletableFuture<ApprovalDecision> waiter = CompletableFuture.supplyAsync(() -> semaphore.awaitDecision("wf-1"));

        semaphore.submitDecision("wf-1", ApprovalDecision.APPROVE);

        assertEquals(ApprovalDecision.APPROVE, waiter.get());
    }

    @Test
    void awaitDecisionIsCancelledWhenCancelled() {
        WorkflowApprovalSemaphore semaphore = new WorkflowApprovalSemaphore();

        CompletableFuture<ApprovalDecision> waiter = CompletableFuture.supplyAsync(() -> semaphore.awaitDecision("wf-2"));

        for (int i = 0; i < 100 && !waiter.isDone(); i++) {
            semaphore.cancel("wf-2");
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        ExecutionException executionException = assertThrows(ExecutionException.class, waiter::get);
        assertTrue(executionException.getCause() instanceof CancellationException);
    }
}