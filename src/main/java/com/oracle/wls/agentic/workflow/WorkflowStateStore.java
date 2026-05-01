package com.oracle.wls.agentic.workflow;

import java.util.List;
import java.util.Optional;

public interface WorkflowStateStore {

    WorkflowRecord create(WorkflowRecord workflowRecord);

    WorkflowRecord update(WorkflowRecord workflowRecord);

    Optional<WorkflowRecord> getByWorkflowId(String workflowId);

    Optional<WorkflowRecord> getLatestByDomain(String domain);

    Optional<WorkflowRecord> getActiveByDomain(String domain);

    List<WorkflowRecord> listAll();

    List<WorkflowRecord> listByStatus(WorkflowStatus workflowStatus);
}
