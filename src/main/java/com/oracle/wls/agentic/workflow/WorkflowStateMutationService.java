package com.oracle.wls.agentic.workflow;

import io.helidon.service.registry.Service;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service.Singleton
public class WorkflowStateMutationService {

    private static final System.Logger LOGGER = System.getLogger(WorkflowStateMutationService.class.getName());

    private final WorkflowStateStore workflowStateStore;

    @Service.Inject
    public WorkflowStateMutationService(WorkflowStateStore workflowStateStore) {
        this.workflowStateStore = Objects.requireNonNull(workflowStateStore, "workflowStateStore must not be null");
    }

    public WorkflowRecord markStepInExecution(String workflowId, String stepName, String details) {
        LOGGER.log(System.Logger.Level.INFO,
                "Marking workflow step IN_EXECUTION: workflowId={0}, step={1}",
                workflowId,
                stepName);
        return updateStep(workflowId, stepName, WorkflowStepStatus.IN_EXECUTION, details, false, false);
    }

    public WorkflowRecord markStepCompleted(String workflowId, String stepName, String details) {
        LOGGER.log(System.Logger.Level.INFO,
                "Marking workflow step COMPLETED: workflowId={0}, step={1}",
                workflowId,
                stepName);
        return updateStep(workflowId, stepName, WorkflowStepStatus.COMPLETED, details, true, false);
    }

    public WorkflowRecord markStepFailedAndFailWorkflow(String workflowId, String stepName, String reason) {
        LOGGER.log(System.Logger.Level.WARNING,
                "Marking workflow step FAILED and failing workflow: workflowId={0}, step={1}, reason={2}",
                workflowId,
                stepName,
                reason);
        return updateStep(workflowId, stepName, WorkflowStepStatus.FAILED, reason, true, true);
    }

    public WorkflowRecord failWorkflowIfLastResponseFailed(String workflowId, String stepName, String lastResponse) {
        if (!isFailedStatus(lastResponse)) {
            LOGGER.log(System.Logger.Level.DEBUG,
                    "lastResponse is not failed; skipping workflow failure mutation: workflowId={0}, step={1}",
                    workflowId,
                    stepName);
            return workflowStateStore.getByWorkflowId(workflowId)
                    .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));
        }

        String reason = extractMessage(lastResponse);
        if (reason == null || reason.isBlank()) {
            reason = "Step reported failed status in lastResponse.";
        }
        LOGGER.log(System.Logger.Level.WARNING,
                "Detected failed status in lastResponse; failing workflow: workflowId={0}, step={1}, reason={2}",
                workflowId,
                stepName,
                reason);
        return markStepFailedAndFailWorkflow(workflowId, stepName, reason);
    }

    private WorkflowRecord updateStep(String workflowId,
                                      String stepName,
                                      WorkflowStepStatus stepStatus,
                                      String details,
                                      boolean setEndedAt,
                                      boolean failWorkflow) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId must not be blank");
        }
        if (stepName == null || stepName.isBlank()) {
            throw new IllegalArgumentException("stepName must not be blank");
        }

        WorkflowRecord current = workflowStateStore.getByWorkflowId(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));

        Instant now = Instant.now();
        List<WorkflowStepRecord> existing = current.steps() == null ? List.of() : current.steps();
        List<WorkflowStepRecord> updated = new ArrayList<>(existing.size() + 1);

        boolean replaced = false;
        for (WorkflowStepRecord step : existing) {
            if (step != null && stepName.equalsIgnoreCase(step.name())) {
                updated.add(new WorkflowStepRecord(
                        step.stepId() == null || step.stepId().isBlank() ? stepName : step.stepId(),
                        step.name(),
                        stepStatus,
                        step.startedAt() == null ? now : step.startedAt(),
                        setEndedAt ? now : null,
                        details));
                replaced = true;
            } else {
                updated.add(step);
            }
        }

        if (!replaced) {
            updated.add(new WorkflowStepRecord(
                    stepName,
                    stepName,
                    stepStatus,
                    now,
                    setEndedAt ? now : null,
                    details));
            LOGGER.log(System.Logger.Level.DEBUG,
                    "Added new workflow step record: workflowId={0}, step={1}, status={2}",
                    workflowId,
                    stepName,
                    stepStatus);
        } else {
            LOGGER.log(System.Logger.Level.DEBUG,
                    "Updated existing workflow step record: workflowId={0}, step={1}, status={2}",
                    workflowId,
                    stepName,
                    stepStatus);
        }

        WorkflowRecord mutated = new WorkflowRecord(
                current.workflowId(),
                current.domain(),
                failWorkflow ? WorkflowStatus.FAILED : current.currentState(),
                current.createdAt(),
                now,
                current.conversationId(),
                current.taskId(),
                current.requestSummary(),
                current.approvalDecision(),
                current.approvalDecisionAt(),
                current.approvalChannel(),
                failWorkflow ? details : current.failureReason(),
                List.copyOf(updated),
                current.workflowSummary(),
                current.reportUrl(),
                current.reportAnalysis());

        LOGGER.log(System.Logger.Level.INFO,
                "Persisting workflow mutation: workflowId={0}, step={1}, stepStatus={2}, workflowState={3}",
                workflowId,
                stepName,
                stepStatus,
                mutated.currentState());
        return workflowStateStore.update(mutated);
    }

    public WorkflowRecord updateWorkflowCompletionDetails(String workflowId,
                                                          WorkflowStatus workflowStatus,
                                                          String workflowSummary,
                                                          String reportUrl,
                                                          String reportAnalysis,
                                                          String failureReason) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId must not be blank");
        }
        WorkflowRecord current = workflowStateStore.getByWorkflowId(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));
        Instant now = Instant.now();
        WorkflowRecord mutated = new WorkflowRecord(
                current.workflowId(),
                current.domain(),
                workflowStatus == null ? current.currentState() : workflowStatus,
                current.createdAt(),
                now,
                current.conversationId(),
                current.taskId(),
                current.requestSummary(),
                current.approvalDecision(),
                current.approvalDecisionAt(),
                current.approvalChannel(),
                failureReason == null ? current.failureReason() : failureReason,
                current.steps() == null ? List.of() : List.copyOf(current.steps()),
                workflowSummary == null ? current.workflowSummary() : workflowSummary,
                reportUrl == null ? current.reportUrl() : reportUrl,
                reportAnalysis == null ? current.reportAnalysis() : reportAnalysis);
        return workflowStateStore.update(mutated);
    }

    private static boolean isFailedStatus(String lastResponse) {
        JsonObject json = parseJson(lastResponse);
        if (json == null || !json.containsKey("status") || json.isNull("status")) {
            return false;
        }
        String status = json.getString("status", "").trim().toLowerCase(Locale.ROOT);
        return "failed".equals(status);
    }

    private static String extractMessage(String lastResponse) {
        JsonObject json = parseJson(lastResponse);
        if (json == null || !json.containsKey("message") || json.isNull("message")) {
            return null;
        }
        return json.getString("message", null);
    }

    private static JsonObject parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        try (JsonReader reader = Json.createReader(new StringReader(trimmed))) {
            return reader.readObject();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
