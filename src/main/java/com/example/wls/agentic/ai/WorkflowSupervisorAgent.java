package com.example.wls.agentic.ai;

import com.example.wls.agentic.workflow.WorkflowRecord;
import io.helidon.service.registry.Service;

import java.util.Objects;

/**
 * Phase 6 execution supervisor abstraction.
 *
 * For the POC this implementation is intentionally deterministic and lightweight:
 * it can be swapped later with a richer multi-step LangChain4j workflow.
 */
@Service.Singleton
public class WorkflowSupervisorAgent {

    /**
     * Execute approved workflow steps.
     *
     * @param workflow workflow to execute
     * @throws IllegalStateException when a controlled failure is requested
     */
    public void execute(WorkflowRecord workflow) {
        Objects.requireNonNull(workflow, "workflow must not be null");

        // Controlled test hook for failure-path validation.
        String summary = workflow.requestSummary();
        if (summary != null && summary.toUpperCase().contains("FORCE_FAIL")) {
            throw new IllegalStateException("Controlled execution failure requested");
        }
    }
}