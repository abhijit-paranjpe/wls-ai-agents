package com.oracle.wls.agentic.rest;

import com.oracle.wls.agentic.dto.ApprovalRequest;
import com.oracle.wls.agentic.dto.ApprovalResponse;
import com.oracle.wls.agentic.dto.WorkflowSummaryListResponse;
import com.oracle.wls.agentic.workflow.ApprovalDecision;
import com.oracle.wls.agentic.workflow.PatchingWorkflowCoordinator;
import com.oracle.wls.agentic.workflow.WorkflowApprovalSemaphore;
import com.oracle.wls.agentic.workflow.WorkflowChannel;
import com.oracle.wls.agentic.workflow.WorkflowRecord;
import io.helidon.http.Http;
import io.helidon.service.registry.Service;
import io.helidon.webserver.http.RestServer;

import java.util.Optional;

import static io.helidon.common.media.type.MediaTypes.APPLICATION_JSON_VALUE;

@RestServer.Endpoint
@Http.Path("/patching")
@Service.Singleton
public class PatchingWorkflowEndpoint {

    private final PatchingWorkflowCoordinator coordinator;
    private final WorkflowApprovalSemaphore approvalSemaphore;

    @Service.Inject
    public PatchingWorkflowEndpoint(PatchingWorkflowCoordinator coordinator,
                                    WorkflowApprovalSemaphore approvalSemaphore) {
        this.coordinator = coordinator;
        this.approvalSemaphore = approvalSemaphore;
    }

    @Http.POST
    @Http.Path("/workflows/{workflowId}/approval")
    @Http.Produces(APPLICATION_JSON_VALUE)
    public ApprovalResponse submitApproval(@Http.PathParam("workflowId") String workflowId,
                                           @Http.Entity ApprovalRequest request) {
        if (request == null || request.decision() == null) {
            throw new IllegalArgumentException("approval decision must be provided");
        }

        Optional<WorkflowRecord> updated = coordinator.applyApprovalDecision(
                workflowId,
                request.decision(),
                WorkflowChannel.API);

        WorkflowRecord workflow = updated.orElseThrow(() -> new IllegalArgumentException(
                "workflow not found or not awaiting approval: " + workflowId));

        approvalSemaphore.submitDecision(workflow.workflowId(), request.decision());

        if (request.decision() == ApprovalDecision.APPROVE) {
            coordinator.submitApprovedWorkflowForExecution(workflow.workflowId());
        }

        return new ApprovalResponse(
                workflow.workflowId(),
                workflow.domain(),
                request.decision(),
                workflow.currentState(),
                guidanceFor(workflow));
    }

    @Http.GET
    @Http.Path("/workflows/{workflowId}")
    @Http.Produces(APPLICATION_JSON_VALUE)
    public WorkflowRecord getById(@Http.PathParam("workflowId") String workflowId) {
        return coordinator.getByWorkflowId(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));
    }

    @Http.GET
    @Http.Path("/workflows/by-domain/{domain}/latest")
    @Http.Produces(APPLICATION_JSON_VALUE)
    public WorkflowRecord getLatestByDomain(@Http.PathParam("domain") String domain) {
        return coordinator.getLatestByDomain(domain)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found for domain: " + domain));
    }

    @Http.GET
    @Http.Path("/workflows/by-domain/{domain}/active")
    @Http.Produces(APPLICATION_JSON_VALUE)
    public WorkflowRecord getActiveByDomain(@Http.PathParam("domain") String domain) {
        return coordinator.getActiveByDomain(domain)
                .orElseThrow(() -> new IllegalArgumentException("active workflow not found for domain: " + domain));
    }

    @Http.GET
    @Http.Path("/workflows")
    @Http.Produces(APPLICATION_JSON_VALUE)
    public WorkflowSummaryListResponse listAll() {
        return new WorkflowSummaryListResponse(coordinator.listAll());
    }

    @Http.GET
    @Http.Path("/workflows/pending-approval")
    @Http.Produces(APPLICATION_JSON_VALUE)
    public WorkflowSummaryListResponse listPendingApproval() {
        return new WorkflowSummaryListResponse(coordinator.listPendingApproval());
    }

    @Http.GET
    @Http.Path("/workflows/in-execution")
    @Http.Produces(APPLICATION_JSON_VALUE)
    public WorkflowSummaryListResponse listInExecution() {
        return new WorkflowSummaryListResponse(coordinator.listInExecution());
    }

    private static String guidanceFor(WorkflowRecord workflow) {
        return switch (workflow.currentState()) {
            case APPROVED -> "Approval recorded. Workflow is approved and can proceed to execution.";
            case REJECTED -> "Approval recorded. Workflow was rejected and is now terminal.";
            default -> "Approval recorded.";
        };
    }
}