package com.example.wls.agentic.workflow;

import com.example.wls.agentic.ai.WorkflowExecutionSequenceAgent;
import com.example.wls.agentic.ai.ApplyLatestPatchesWorkflowService;
import com.example.wls.agentic.ai.RollbackLatestPatchesWorkflowService;
import com.example.wls.agentic.ai.WorkflowExecutionPlan;
import io.helidon.service.registry.Service;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service.Singleton
public class PatchingWorkflowCoordinator {

    private static final List<String> REQUIRED_COMPLETION_STEPS = List.of(
            "stop servers",
            "apply patches",
            "start servers",
            "verify patch level");

    private static final Set<WorkflowStatus> IN_EXECUTION_STATES = Set.of(
            WorkflowStatus.APPROVED,
            WorkflowStatus.QUEUED,
            WorkflowStatus.IN_EXECUTION);
    private static final Duration DEFAULT_COMPLETION_EVALUATION_TIMEOUT = Duration.ofMinutes(30);

    private final WorkflowStateStore workflowStateStore;
    private final DomainLockManager domainLockManager;
    private final WorkflowExecutionSequenceAgent workflowExecutionSequenceAgent;
    private final ApplyLatestPatchesWorkflowService applyLatestPatchesWorkflowService;
    private final RollbackLatestPatchesWorkflowService rollbackLatestPatchesWorkflowService;
    private final ScheduledExecutorService workflowExecutor;
    private final Duration completionEvaluationTimeout;

    @Service.Inject
    public PatchingWorkflowCoordinator(WorkflowStateStore workflowStateStore,
                                       DomainLockManager domainLockManager,
                                       WorkflowExecutionSequenceAgent workflowExecutionSequenceAgent,
                                       ApplyLatestPatchesWorkflowService applyLatestPatchesWorkflowService,
                                       RollbackLatestPatchesWorkflowService rollbackLatestPatchesWorkflowService) {
        this(workflowStateStore,
                domainLockManager,
                workflowExecutionSequenceAgent,
                applyLatestPatchesWorkflowService,
                rollbackLatestPatchesWorkflowService,
                Executors.newScheduledThreadPool(2),
                DEFAULT_COMPLETION_EVALUATION_TIMEOUT);
    }

    public PatchingWorkflowCoordinator(WorkflowStateStore workflowStateStore) {
        this(workflowStateStore,
                new InMemoryDomainLockManager(),
                null,
                null,
                null,
                Executors.newScheduledThreadPool(2),
                DEFAULT_COMPLETION_EVALUATION_TIMEOUT);
    }

    PatchingWorkflowCoordinator(WorkflowStateStore workflowStateStore,
                                DomainLockManager domainLockManager,
                                WorkflowExecutionSequenceAgent workflowExecutionSequenceAgent,
                                ApplyLatestPatchesWorkflowService applyLatestPatchesWorkflowService,
                                RollbackLatestPatchesWorkflowService rollbackLatestPatchesWorkflowService,
                                ScheduledExecutorService workflowExecutor,
                                Duration completionEvaluationTimeout) {
        this.workflowStateStore = Objects.requireNonNull(workflowStateStore, "workflowStateStore must not be null");
        this.domainLockManager = Objects.requireNonNull(domainLockManager, "domainLockManager must not be null");
        this.workflowExecutionSequenceAgent = workflowExecutionSequenceAgent;
        this.applyLatestPatchesWorkflowService = applyLatestPatchesWorkflowService;
        this.rollbackLatestPatchesWorkflowService = rollbackLatestPatchesWorkflowService;
        this.workflowExecutor = Objects.requireNonNull(workflowExecutor, "workflowExecutor must not be null");
        this.completionEvaluationTimeout = completionEvaluationTimeout == null
                ? DEFAULT_COMPLETION_EVALUATION_TIMEOUT
                : completionEvaluationTimeout;
    }

    public PatchingWorkflowProposalResult createProposal(String domain,
                                                         String conversationId,
                                                         String taskId,
                                                         String requestSummary) {
        validateDomain(domain);

        Optional<WorkflowRecord> active = workflowStateStore.getActiveByDomain(domain);
        if (active.isPresent()) {
            WorkflowRecord existing = active.orElseThrow();
            return PatchingWorkflowProposalResult.conflict(existing.workflowId(), existing);
        }

        return PatchingWorkflowProposalResult.createdPending(domain);
    }

    public Optional<WorkflowRecord> getByWorkflowId(String workflowId) {
        return workflowStateStore.getByWorkflowId(workflowId);
    }

    public Optional<WorkflowRecord> getLatestByDomain(String domain) {
        return workflowStateStore.getLatestByDomain(domain);
    }

    public Optional<WorkflowRecord> getActiveByDomain(String domain) {
        return workflowStateStore.getActiveByDomain(domain);
    }

    public List<WorkflowSummary> listAll() {
        return workflowStateStore.listAll().stream()
                .map(WorkflowRecord::toSummary)
                .toList();
    }

    public List<WorkflowSummary> listPendingApproval() {
        return workflowStateStore.listByStatus(WorkflowStatus.AWAITING_APPROVAL).stream()
                .map(WorkflowRecord::toSummary)
                .toList();
    }

    public List<WorkflowSummary> listInExecution() {
        return IN_EXECUTION_STATES.stream()
                .flatMap(state -> workflowStateStore.listByStatus(state).stream())
                .sorted((a, b) -> {
                    Instant left = a.updatedAt() == null ? Instant.EPOCH : a.updatedAt();
                    Instant right = b.updatedAt() == null ? Instant.EPOCH : b.updatedAt();
                    return right.compareTo(left);
                })
                .map(WorkflowRecord::toSummary)
                .toList();
    }

    public Optional<WorkflowRecord> applyApprovalDecision(String workflowId,
                                                          ApprovalDecision decision,
                                                          WorkflowChannel channel) {
        validateWorkflowId(workflowId);
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(channel, "channel must not be null");

        Optional<WorkflowRecord> existing = workflowStateStore.getByWorkflowId(workflowId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        WorkflowRecord current = existing.orElseThrow();
        if (current.currentState() != WorkflowStatus.AWAITING_APPROVAL) {
            return Optional.empty();
        }

        WorkflowStatus nextStatus = switch (decision) {
            case APPROVE -> WorkflowStatus.APPROVED;
            case REJECT -> WorkflowStatus.REJECTED;
            case CANCEL -> WorkflowStatus.REJECTED;
        };

        WorkflowRecord updated = new WorkflowRecord(
                current.workflowId(),
                current.domain(),
                nextStatus,
                current.createdAt(),
                Instant.now(),
                current.conversationId(),
                current.taskId(),
                current.requestSummary(),
                decision,
                Instant.now(),
                channel,
                current.failureReason(),
                current.steps());

        return Optional.of(workflowStateStore.update(updated));
    }

    public Optional<WorkflowRecord> applyApprovalDecisionByDomain(String domain,
                                                                  ApprovalDecision decision,
                                                                  WorkflowChannel channel,
                                                                  String conversationId,
                                                                  String taskId,
                                                                  String requestSummary) {
        validateDomain(domain);
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(channel, "channel must not be null");

        Optional<WorkflowRecord> active = workflowStateStore.getActiveByDomain(domain);
        if (active.isPresent()) {
            WorkflowRecord existing = active.orElseThrow();
            if (existing.currentState() == WorkflowStatus.AWAITING_APPROVAL) {
                return applyApprovalDecision(existing.workflowId(), decision, channel);
            }
            return Optional.empty();
        }

        if (decision != ApprovalDecision.APPROVE) {
            return Optional.empty();
        }

        WorkflowRecord created = createWorkflowRecord(
                domain,
                conversationId,
                taskId,
                requestSummary,
                ApprovalDecision.APPROVE,
                channel,
                WorkflowStatus.APPROVED);

        return Optional.of(created);
    }

    private WorkflowRecord createWorkflowRecord(String domain,
                                                String conversationId,
                                                String taskId,
                                                String requestSummary,
                                                ApprovalDecision approvalDecision,
                                                WorkflowChannel approvalChannel,
                                                WorkflowStatus finalState) {
        Instant now = Instant.now();
        String workflowId = UUID.randomUUID().toString();

        WorkflowStatus decidedState = finalState == null ? WorkflowStatus.APPROVED : finalState;
        WorkflowRecord created = new WorkflowRecord(
                workflowId,
                domain,
                decidedState,
                now,
                now,
                conversationId,
                taskId,
                requestSummary,
                approvalDecision,
                approvalDecision == null ? null : now,
                approvalDecision == null ? null : approvalChannel,
                null,
                List.of());
        return workflowStateStore.create(created);
    }

    public Optional<WorkflowRecord> submitApprovedWorkflowForExecution(String workflowId) {
        validateWorkflowId(workflowId);

        Optional<WorkflowRecord> existing = workflowStateStore.getByWorkflowId(workflowId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        WorkflowRecord current = existing.orElseThrow();
        if (current.currentState() != WorkflowStatus.APPROVED) {
            return Optional.empty();
        }

        WorkflowRecord queued = workflowStateStore.update(withState(current, WorkflowStatus.QUEUED, null));
        workflowExecutor.submit(() -> executeQueuedWorkflow(queued.workflowId()));
        return Optional.of(queued);
    }

    private void executeQueuedWorkflow(String workflowId) {
        Optional<WorkflowRecord> maybeQueued = workflowStateStore.getByWorkflowId(workflowId);
        if (maybeQueued.isEmpty()) {
            return;
        }

        WorkflowRecord queued = maybeQueued.orElseThrow();
        if (queued.currentState() != WorkflowStatus.QUEUED || queued.approvalDecision() != ApprovalDecision.APPROVE) {
            workflowStateStore.update(withState(
                    queued,
                    WorkflowStatus.FAILED,
                    "Workflow execution requires explicit APPROVE decision and QUEUED state."));
            return;
        }
        String domain = queued.domain();
        boolean lockAcquired = false;
        try {
            if (!domainLockManager.acquire(domain, workflowId)) {
                String owner = domainLockManager.lockOwner(domain);
                workflowStateStore.update(withState(
                        queued,
                        WorkflowStatus.FAILED,
                        "Domain lock is already held for domain '" + domain + "' by workflow '" + owner + "'."));
                return;
            }
            lockAcquired = true;

            WorkflowRecord inExecution = workflowStateStore.update(withState(queued, WorkflowStatus.IN_EXECUTION, null));
            scheduleCompletionEvaluation(inExecution.workflowId());
            if (workflowExecutionSequenceAgent == null) {
                throw new IllegalStateException("WorkflowExecutionSequenceAgent is not configured");
            }
            WorkflowExecutionPlan executionPlan = resolveExecutionPlan(inExecution);
            String sequenceResponse = workflowExecutionSequenceAgent.run(
                    inExecution.workflowId(),
                    inExecution.domain(),
                    "Execute approved patching workflow for domain " + inExecution.domain(),
                    "Can you initiate stop servers for domain " + inExecution.domain()
                            + " and return host and pids in response?",
                    executionPlan);

            if (isFailedStatus(sequenceResponse)) {
                String reason = extractMessage(sequenceResponse);
                if (reason == null || reason.isBlank()) {
                    reason = "Workflow execution sequence returned failed status.";
                }
                WorkflowRecord latest = workflowStateStore.getByWorkflowId(workflowId).orElse(inExecution);
                workflowStateStore.update(withState(latest, WorkflowStatus.FAILED, reason));
                return;
            }

            evaluateCompletionImmediately(workflowId);
        } catch (RuntimeException ex) {
            WorkflowRecord latest = workflowStateStore.getByWorkflowId(workflowId).orElse(queued);
            workflowStateStore.update(withState(latest, WorkflowStatus.FAILED, ex.getMessage()));
        } finally {
            if (lockAcquired) {
                domainLockManager.release(domain, workflowId);
            }
        }
    }

    private void scheduleCompletionEvaluation(String workflowId) {
        long delayMillis = Math.max(1L, completionEvaluationTimeout.toMillis());
        workflowExecutor.schedule(() -> evaluateCompletionOnTimeout(workflowId), delayMillis, TimeUnit.MILLISECONDS);
    }

    private void evaluateCompletionImmediately(String workflowId) {
        Optional<WorkflowRecord> maybeLatest = workflowStateStore.getByWorkflowId(workflowId);
        if (maybeLatest.isEmpty()) {
            return;
        }

        WorkflowRecord latest = maybeLatest.orElseThrow();
        if (latest.currentState() == WorkflowStatus.COMPLETED
                || latest.currentState() == WorkflowStatus.FAILED
                || latest.currentState() == WorkflowStatus.REJECTED) {
            return;
        }

        String completionFailureReason = detectCompletionFailureReason(latest);
        if (completionFailureReason != null) {
            return;
        }

        workflowStateStore.update(withState(latest, WorkflowStatus.COMPLETED, null));
    }

    private void evaluateCompletionOnTimeout(String workflowId) {
        Optional<WorkflowRecord> maybeLatest = workflowStateStore.getByWorkflowId(workflowId);
        if (maybeLatest.isEmpty()) {
            return;
        }

        WorkflowRecord latest = maybeLatest.orElseThrow();
        if (latest.currentState() == WorkflowStatus.COMPLETED
                || latest.currentState() == WorkflowStatus.FAILED
                || latest.currentState() == WorkflowStatus.REJECTED) {
            return;
        }

        String completionFailureReason = detectCompletionFailureReason(latest);
        if (completionFailureReason != null) {
            workflowStateStore.update(withState(
                    latest,
                    WorkflowStatus.FAILED,
                    completionFailureReason + " Timeout elapsed after " + completionEvaluationTimeout.toMinutes() + " minutes."));
            return;
        }

        workflowStateStore.update(withState(latest, WorkflowStatus.COMPLETED, null));
    }

    private static WorkflowRecord withState(WorkflowRecord current,
                                            WorkflowStatus nextStatus,
                                            String failureReason) {
        return new WorkflowRecord(
                current.workflowId(),
                current.domain(),
                nextStatus,
                current.createdAt(),
                Instant.now(),
                current.conversationId(),
                current.taskId(),
                current.requestSummary(),
                current.approvalDecision(),
                current.approvalDecisionAt(),
                current.approvalChannel(),
                failureReason,
                current.steps());
    }

    private static String detectCompletionFailureReason(WorkflowRecord record) {
        List<WorkflowStepRecord> steps = record.steps();
        if (steps == null || steps.isEmpty()) {
            return "Workflow execution completed without recorded step history; refusing to mark COMPLETED.";
        }

        for (String expected : REQUIRED_COMPLETION_STEPS) {
            boolean completed = steps.stream()
                    .filter(Objects::nonNull)
                    .anyMatch(step -> step.state() == WorkflowStepStatus.COMPLETED
                            && normalizeStep(step.name()).equals(expected));
            if (!completed) {
                return "Workflow did not complete required step: " + expected;
            }
        }

        return null;
    }

    private static String normalizeStep(String stepName) {
        if (stepName == null || stepName.isBlank()) {
            return "";
        }
        String candidate = stepName.trim().toLowerCase(Locale.ROOT);
        if (candidate.contains("stop") && candidate.contains("server")) {
            return "stop servers";
        }
        if (candidate.contains("apply") && candidate.contains("patch")) {
            return "apply patches";
        }
        if (candidate.contains("rollback") && candidate.contains("patch")) {
            return "apply patches";
        }
        if (candidate.contains("start") && candidate.contains("server")) {
            return "start servers";
        }
        if (candidate.contains("verify") && candidate.contains("patch")) {
            return "verify patch level";
        }
        return candidate;
    }

    private static boolean isFailedStatus(String response) {
        JsonObject json = parseJson(response);
        if (json == null || !json.containsKey("status") || json.isNull("status")) {
            return false;
        }
        String status = json.getString("status", "").trim().toLowerCase(Locale.ROOT);
        return "failed".equals(status);
    }

    private static String extractMessage(String response) {
        JsonObject json = parseJson(response);
        if (json == null || !json.containsKey("message") || json.isNull("message")) {
            return null;
        }
        return json.getString("message", null);
    }

    private static JsonObject parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try (JsonReader reader = Json.createReader(new StringReader(raw.trim()))) {
            return reader.readObject();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void validateDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain must not be blank");
        }
    }

    private static void validateWorkflowId(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId must not be blank");
        }
    }

    private WorkflowExecutionPlan resolveExecutionPlan(WorkflowRecord record) {
        String summary = record == null || record.requestSummary() == null
                ? ""
                : record.requestSummary().toLowerCase(Locale.ROOT);
        boolean rollbackRequested = summary.contains("rollback")
                || summary.contains("roll back")
                || summary.contains("revert patch");

        if (rollbackRequested) {
            if (rollbackLatestPatchesWorkflowService == null) {
                throw new IllegalStateException("RollbackLatestPatchesWorkflowService is not configured");
            }
            return rollbackLatestPatchesWorkflowService.plan();
        }

        if (applyLatestPatchesWorkflowService == null) {
            throw new IllegalStateException("ApplyLatestPatchesWorkflowService is not configured");
        }
        return applyLatestPatchesWorkflowService.plan();
    }
}
