package com.oracle.wls.agentic.workflow;

import io.helidon.service.registry.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service.Singleton
public class InMemoryWorkflowStateStore implements WorkflowStateStore {

    private static final EnumSet<WorkflowStatus> TERMINAL_STATES = EnumSet.of(
            WorkflowStatus.COMPLETED,
            WorkflowStatus.FAILED,
            WorkflowStatus.REJECTED,
            WorkflowStatus.CANCELLED,
            WorkflowStatus.APPROVAL_TIMED_OUT,
            WorkflowStatus.EXECUTION_TIMED_OUT);

    private static final Comparator<WorkflowRecord> RECENCY_ORDER =
            Comparator.comparing(InMemoryWorkflowStateStore::safeUpdatedAt)
                    .thenComparing(InMemoryWorkflowStateStore::safeCreatedAt);

    private final Map<String, WorkflowRecord> recordsById = new ConcurrentHashMap<>();

    @Override
    public WorkflowRecord create(WorkflowRecord workflowRecord) {
        WorkflowRecord normalized = normalize(workflowRecord);
        WorkflowRecord existing = recordsById.putIfAbsent(normalized.workflowId(), normalized);
        if (existing != null) {
            throw new IllegalArgumentException("Workflow already exists: " + normalized.workflowId());
        }
        return normalized;
    }

    @Override
    public WorkflowRecord update(WorkflowRecord workflowRecord) {
        WorkflowRecord normalized = normalize(workflowRecord);
        WorkflowRecord updated = recordsById.compute(normalized.workflowId(), (ignored, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("Workflow not found: " + normalized.workflowId());
            }
            return normalized;
        });
        return Objects.requireNonNull(updated);
    }

    @Override
    public Optional<WorkflowRecord> getByWorkflowId(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(recordsById.get(workflowId));
    }

    @Override
    public Optional<WorkflowRecord> getLatestByDomain(String domain) {
        String normalizedDomain = normalizeDomain(domain);
        if (normalizedDomain == null) {
            return Optional.empty();
        }
        return recordsById.values().stream()
                .filter(record -> normalizedDomain.equals(normalizeDomain(record.domain())))
                .max(RECENCY_ORDER);
    }

    @Override
    public Optional<WorkflowRecord> getActiveByDomain(String domain) {
        String normalizedDomain = normalizeDomain(domain);
        if (normalizedDomain == null) {
            return Optional.empty();
        }
        return recordsById.values().stream()
                .filter(record -> normalizedDomain.equals(normalizeDomain(record.domain())))
                .filter(record -> !isTerminal(record.currentState()))
                .max(RECENCY_ORDER);
    }

    @Override
    public List<WorkflowRecord> listAll() {
        return recordsById.values().stream()
                .sorted(RECENCY_ORDER.reversed())
                .toList();
    }

    @Override
    public List<WorkflowRecord> listByStatus(WorkflowStatus workflowStatus) {
        if (workflowStatus == null) {
            return List.of();
        }
        return recordsById.values().stream()
                .filter(record -> workflowStatus == record.currentState())
                .sorted(RECENCY_ORDER.reversed())
                .toList();
    }

    private static boolean isTerminal(WorkflowStatus status) {
        return status != null && TERMINAL_STATES.contains(status);
    }

    private static WorkflowRecord normalize(WorkflowRecord workflowRecord) {
        Objects.requireNonNull(workflowRecord, "workflowRecord must not be null");
        if (workflowRecord.workflowId() == null || workflowRecord.workflowId().isBlank()) {
            throw new IllegalArgumentException("workflowId must not be blank");
        }
        if (workflowRecord.domain() == null || workflowRecord.domain().isBlank()) {
            throw new IllegalArgumentException("domain must not be blank");
        }
        return new WorkflowRecord(
                workflowRecord.workflowId(),
                workflowRecord.domain(),
                workflowRecord.currentState(),
                workflowRecord.createdAt(),
                workflowRecord.updatedAt(),
                workflowRecord.conversationId(),
                workflowRecord.taskId(),
                workflowRecord.requestSummary(),
                workflowRecord.approvalDecision(),
                workflowRecord.approvalDecisionAt(),
                workflowRecord.approvalChannel(),
                workflowRecord.failureReason(),
                workflowRecord.steps() == null ? List.of() : List.copyOf(workflowRecord.steps()),
                workflowRecord.workflowSummary(),
                workflowRecord.reportUrl(),
                workflowRecord.reportAnalysis());
    }

    private static String normalizeDomain(String domain) {
        if (domain == null) {
            return null;
        }
        String normalized = domain.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static Instant safeUpdatedAt(WorkflowRecord record) {
        return record.updatedAt() == null ? Instant.EPOCH : record.updatedAt();
    }

    private static Instant safeCreatedAt(WorkflowRecord record) {
        return record.createdAt() == null ? Instant.EPOCH : record.createdAt();
    }
}