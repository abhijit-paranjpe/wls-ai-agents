package com.example.wls.agentic.ai;

import io.helidon.service.registry.Service;

import java.util.List;

@Service.Singleton
public class ApplyLatestPatchesWorkflowService implements PatchingWorkflowPlanProvider {

    private static final WorkflowExecutionPlan PLAN = new WorkflowExecutionPlan(
            "apply-latest-patches-workflow",
            List.of(
                    new WorkflowExecutionStep(1,
                            "initiate-stop-servers",
                            "Stop all relevant servers for domain %s and return strict JSON including hostPids for every async stop operation.",
                            WorkflowStepAgentType.DOMAIN_RUNTIME,
                            null),
                    new WorkflowExecutionStep(2,
                            "monitor-stop-completion",
                            "Using hostPids from lastResponse, monitor stop completion for domain %s and return strict JSON with factual status only.",
                            WorkflowStepAgentType.MONITORING,
                            null),
                    new WorkflowExecutionStep(3,
                            "apply-latest-patches",
                            "Apply the latest recommended patches for domain %s and return strict JSON with factual execution details and any tracking identifiers produced by tools.",
                            WorkflowStepAgentType.PATCHING,
                            null),
                    new WorkflowExecutionStep(4,
                            "monitor-patch-completion",
                            "Using tracking data from lastResponse, monitor patch application completion for domain %s and return strict JSON with factual status only.",
                            WorkflowStepAgentType.MONITORING,
                            "apply-latest-patches"),
                    new WorkflowExecutionStep(5,
                            "initiate-start-servers",
                            "Start all required servers for domain %s and return strict JSON including hostPids for every async start operation.",
                            WorkflowStepAgentType.DOMAIN_RUNTIME,
                            null),
                    new WorkflowExecutionStep(6,
                            "monitor-start-completion",
                            "Using hostPids from lastResponse, monitor server start completion for domain %s and return strict JSON with factual status only.",
                            WorkflowStepAgentType.MONITORING,
                            null),
                    new WorkflowExecutionStep(7,
                            "verify-domain-patch-level",
                            "Verify the current patch level for domain %s and return strict JSON with factual verification results only.",
                            WorkflowStepAgentType.PATCHING,
                            null)));

    @Override
    public WorkflowExecutionPlan plan() {
        return PLAN;
    }
}
