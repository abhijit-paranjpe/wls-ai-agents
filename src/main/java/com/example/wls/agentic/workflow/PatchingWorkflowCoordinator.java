package com.example.wls.agentic.workflow;

import io.helidon.service.registry.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service.Singleton
public class PatchingWorkflowCoordinator {

    private static final Set<WorkflowStatus> IN_EXECUTION_STATES = Set.of(
            WorkflowStatus.APPROVED,
            WorkflowStatus.QUEUED,
            WorkflowStatus.IN_EXECUTION);

    private final WorkflowStateStore workflowStateStore;

    @Service.Inject
    public PatchingWorkflowCoordinator(WorkflowStateStore workflowStateStore) {
        this.workflowStateStore = Objects.requireNonNull(workflowStateStore, "workflowStateStore must not be null");
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

        Instant now = Instant.now();
        String workflowId = UUID.randomUUID().toString();

        WorkflowRecord draft = new WorkflowRecord(
                workflowId,
                domain,
                WorkflowStatus.DRAFT,
                now,
                now,
                conversationId,
                taskId,
                requestSummary,
                null,
                null,
                null,
                null,
                List.of());
        workflowStateStore.create(draft);

        WorkflowRecord proposed = new WorkflowRecord(
                draft.workflowId(),
                draft.domain(),
                WorkflowStatus.PROPOSED,
                draft.createdAt(),
                Instant.now(),
                draft.conversationId(),
                draft.taskId(),
                draft.requestSummary(),
                draft.approvalDecision(),
                draft.approvalDecisionAt(),
                draft.approvalChannel(),
                draft.failureReason(),
                draft.steps());
        workflowStateStore.update(proposed);

        WorkflowRecord awaitingApproval = new WorkflowRecord(
                proposed.workflowId(),
                proposed.domain(),
                WorkflowStatus.AWAITING_APPROVAL,
                proposed.createdAt(),
                Instant.now(),
                proposed.conversationId(),
                proposed.taskId(),
                proposed.requestSummary(),
                proposed.approvalDecision(),
                proposed.approvalDecisionAt(),
                proposed.approvalChannel(),
                proposed.failureReason(),
                proposed.steps());
        WorkflowRecord persisted = workflowStateStore.update(awaitingApproval);

        return PatchingWorkflowProposalResult.created(persisted);
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
            case CANCEL -> WorkflowStatus.CANCELLED;
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
}
