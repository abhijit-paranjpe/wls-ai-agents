package com.example.wls.agentic.workflow;

import io.helidon.service.registry.Service;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service.Singleton
public class WorkflowApprovalSemaphore {

    private final ConcurrentMap<String, CompletableFuture<ApprovalDecision>> pending = new ConcurrentHashMap<>();

    public ApprovalDecision awaitDecision(String workflowId) {
        CompletableFuture<ApprovalDecision> future = pending.computeIfAbsent(workflowId, ignored -> new CompletableFuture<>());
        try {
            return future.join();
        } finally {
            pending.remove(workflowId, future);
        }
    }

    public boolean submitDecision(String workflowId, ApprovalDecision decision) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId must not be blank");
        }
        if (decision == null) {
            throw new IllegalArgumentException("decision must not be null");
        }
        CompletableFuture<ApprovalDecision> future = pending.computeIfAbsent(workflowId, ignored -> new CompletableFuture<>());
        return future.complete(decision);
    }

    public void cancel(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return;
        }
        CompletableFuture<ApprovalDecision> future = pending.remove(workflowId);
        if (future != null) {
            future.completeExceptionally(new CancellationException("Workflow approval cancelled: " + workflowId));
        }
    }
}