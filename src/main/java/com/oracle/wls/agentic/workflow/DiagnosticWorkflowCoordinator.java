package com.oracle.wls.agentic.workflow;

import com.oracle.wls.agentic.ai.DiagnosticAgent;
import com.oracle.wls.agentic.ai.MonitoringAgent;
import io.helidon.service.registry.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service.Singleton
public class DiagnosticWorkflowCoordinator {

    private static final Logger LOGGER = Logger.getLogger(DiagnosticWorkflowCoordinator.class.getName());

    private static final String DEFAULT_DIAGNOSTIC_DOMAIN = "global-diagnostics";
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)\\bhttps?://[^\\s)>\"]+");
    private static final Pattern HOST_PID_ENTRY_PATTERN = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"");
    private static final int MAX_MONITOR_ATTEMPTS = 60;

    private final WorkflowStateStore workflowStateStore;
    private final WorkflowStateMutationService mutationService;
    private final DiagnosticAgent diagnosticAgent;
    private final MonitoringAgent monitoringAgent;
    private final ExecutorService workflowExecutor;

    @Service.Inject
    public DiagnosticWorkflowCoordinator(WorkflowStateStore workflowStateStore,
                                         WorkflowStateMutationService mutationService,
                                         DiagnosticAgent diagnosticAgent,
                                         MonitoringAgent monitoringAgent) {
        this(workflowStateStore,
                mutationService,
                diagnosticAgent,
                monitoringAgent,
                Executors.newScheduledThreadPool(2));
    }

    DiagnosticWorkflowCoordinator(WorkflowStateStore workflowStateStore,
                                  WorkflowStateMutationService mutationService,
                                  DiagnosticAgent diagnosticAgent,
                                  MonitoringAgent monitoringAgent,
                                  ExecutorService workflowExecutor) {
        this.workflowStateStore = Objects.requireNonNull(workflowStateStore, "workflowStateStore must not be null");
        this.mutationService = Objects.requireNonNull(mutationService, "mutationService must not be null");
        this.diagnosticAgent = Objects.requireNonNull(diagnosticAgent, "diagnosticAgent must not be null");
        this.monitoringAgent = Objects.requireNonNull(monitoringAgent, "monitoringAgent must not be null");
        this.workflowExecutor = Objects.requireNonNull(workflowExecutor, "workflowExecutor must not be null");
    }

    public WorkflowRecord startRdaDiagnosticWorkflow(String domain,
                                                     String conversationId,
                                                     String taskId,
                                                     String requestSummary) {
        String targetDomain = (domain == null || domain.isBlank()) ? DEFAULT_DIAGNOSTIC_DOMAIN : domain;
        Instant now = Instant.now();
        WorkflowRecord created = workflowStateStore.create(new WorkflowRecord(
                UUID.randomUUID().toString(),
                targetDomain,
                WorkflowStatus.QUEUED,
                now,
                now,
                conversationId,
                taskId,
                requestSummary,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null));

        workflowExecutor.submit(() -> execute(created.workflowId()));
        return created;
    }

    public java.util.Optional<WorkflowRecord> getByWorkflowId(String workflowId) {
        return workflowStateStore.getByWorkflowId(workflowId);
    }

    private void execute(String workflowId) {
        WorkflowRecord workflow = workflowStateStore.getByWorkflowId(workflowId).orElse(null);
        if (workflow == null) {
            return;
        }

        try {
            mutationService.updateWorkflowCompletionDetails(workflowId, WorkflowStatus.IN_EXECUTION, null, null, null, null);

            String runStep = "run rda diagnostic report";
            mutationService.markStepInExecution(workflowId, runStep, "Triggering RDA report async job.");
            String runResponse = diagnosticAgent.analyzeRequest(
                    """
                            Run RDA diagnostic report now using run-rda-report-tool for domain %s.
                            Return strict JSON with factual tool-backed details only:
                            {
                              "status": "running|completed|failed",
                              "operation": "run-rda-report",
                              "domain": "%s",
                              "hostPids": {"<host>": "<pid>"},
                              "message": "<factual detail>"
                            }
                            Include hostPids for async tracking.
                            """.formatted(workflow.domain(), workflow.domain()));
            if (WorkflowResponseContract.isFailed(runResponse)) {
                fail(workflowId, runStep, extractMessage(runResponse, "Failed to trigger RDA report async job."));
                return;
            }
            if (!WorkflowResponseContract.hasTrackingContext(runResponse)) {
                fail(workflowId,
                        runStep,
                        "Run RDA diagnostic report did not return required async tracking context (hostPids/domain).");
                return;
            }
            LOGGER.log(Level.INFO,
                    "RDA workflow {0}: tracking host/pid context from run step: {1}",
                    new Object[]{workflowId, extractHostPidSummary(runResponse)});
            mutationService.markStepCompleted(workflowId, runStep, extractMessage(runResponse, "RDA report async job started."));

            String monitorStep = "monitor rda async job";
            mutationService.markStepInExecution(workflowId, monitorStep, "Monitoring async RDA job every 1 minute.");
            boolean completed = false;
            String lastMonitorResponse = null;
            for (int attempt = 1; attempt <= MAX_MONITOR_ATTEMPTS; attempt++) {
                lastMonitorResponse = monitoringAgent.analyzeRequest(WorkflowResponseContract.composePromptWithMonitoringContext(
                        "Monitor RDA async job till completion. Poll once and return factual status.",
                        workflow.domain(),
                        runResponse));
                String status = WorkflowResponseContract.extractStatus(lastMonitorResponse);
                if ("completed".equals(status)) {
                    completed = true;
                    break;
                }
                if ("failed".equals(status) || "not found".equals(status)) {
                    fail(workflowId, monitorStep, extractMessage(lastMonitorResponse,
                            "RDA async job monitoring reported terminal failure."));
                    return;
                }
                try {
                    Thread.sleep(60_000L);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    fail(workflowId, monitorStep, "RDA monitoring interrupted while waiting for next 1-minute poll.");
                    return;
                }
            }
            if (!completed) {
                fail(workflowId, monitorStep, "RDA async job did not complete within monitoring window.");
                return;
            }
            mutationService.markStepCompleted(workflowId, monitorStep,
                    extractMessage(lastMonitorResponse, "RDA async job completed."));

            String analysisStep = "analyze rda report";
            mutationService.markStepInExecution(workflowId, analysisStep, "Fetching report URL and analyzing report.");
            String reportUrlResponse = diagnosticAgent.analyzeRequest(
                    WorkflowResponseContract.composePromptWithMonitoringContext(
                            "Use get-rda-report-tool to fetch generated report URL for this completed RDA run.",
                            workflow.domain(),
                            runResponse));
            String reportUrl = extractFirstUrl(reportUrlResponse);
            LOGGER.log(Level.INFO, "RDA workflow {0}: extracted report URL: {1}", new Object[]{workflowId, reportUrl});
            if (reportUrl == null || reportUrl.isBlank()) {
                fail(workflowId, analysisStep, "Unable to obtain RDA report URL from tool-backed response.");
                return;
            }

            String analysis = diagnosticAgent.analyzeRequest(
                    "Review and analyze RDA report at this URL: " + reportUrl
                            + ". Provide concise summary first with evidence-backed recommendations.");
            if (WorkflowResponseContract.isFailed(analysis)) {
                fail(workflowId, analysisStep, extractMessage(analysis, "RDA report analysis failed."));
                return;
            }
            mutationService.markStepCompleted(workflowId, analysisStep, "RDA report analysis completed.");

            String summary = summarize(analysis);
            mutationService.updateWorkflowCompletionDetails(
                    workflowId,
                    WorkflowStatus.COMPLETED,
                    summary,
                    reportUrl,
                    analysis,
                    null);
        } catch (RuntimeException ex) {
            mutationService.updateWorkflowCompletionDetails(
                    workflowId,
                    WorkflowStatus.FAILED,
                    null,
                    null,
                    null,
                    ex.getMessage());
        }
    }

    private void fail(String workflowId, String stepName, String reason) {
        mutationService.markStepFailedAndFailWorkflow(workflowId, stepName, reason);
        mutationService.updateWorkflowCompletionDetails(workflowId, WorkflowStatus.FAILED, null, null, null, reason);
    }

    private static String summarize(String analysis) {
        if (analysis == null || analysis.isBlank()) {
            return "RDA report analyzed.";
        }
        String trimmed = analysis.trim();
        int newline = trimmed.indexOf('\n');
        String line = newline > 0 ? trimmed.substring(0, newline) : trimmed;
        return line.length() > 300 ? line.substring(0, 300) : line;
    }

    private static String extractMessage(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.length() > 400 ? trimmed.substring(0, 400) : trimmed;
    }

    private static String extractFirstUrl(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String url = matcher.group();
        if (url == null) {
            return null;
        }
        return url.replaceAll("[.,;!?]+$", "");
    }

    private static String extractHostPidSummary(String text) {
        if (text == null || text.isBlank()) {
            return "none";
        }
        Matcher matcher = HOST_PID_ENTRY_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String host = matcher.group(1);
            String pid = matcher.group(2);
            if (host == null || host.isBlank() || pid == null || pid.isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(", ");
            }
            result.append(host).append("=").append(pid);
        }
        return result.isEmpty() ? "none" : result.toString();
    }
}