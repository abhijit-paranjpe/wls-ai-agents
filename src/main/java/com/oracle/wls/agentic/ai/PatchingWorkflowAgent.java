package com.oracle.wls.agentic.ai;

import com.oracle.wls.agentic.workflow.PatchingWorkflowStateKeys;
import com.oracle.wls.agentic.workflow.WorkflowApprovalSemaphore;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;
import io.helidon.integrations.langchain4j.Ai;

@Ai.Agent("patching-workflow")
public interface PatchingWorkflowAgent {

    @SequenceAgent(outputKey = PatchingWorkflowStateKeys.WORKFLOW_RESULT_KEY,
            subAgents = {
                    PatchingAgent.class,
                    HumanApprovalAgent.class,
                    PatchingWorkflowOutcomeRouterAgent.class
            })
    String run(@MemoryId String workflowId,
               @V(PatchingWorkflowStateKeys.TARGET_DOMAIN_KEY) String targetDomain,
               @V("approvalSemaphore") WorkflowApprovalSemaphore approvalSemaphore,
               @V("proposalSummary") String proposalSummary,
               @V("question") String question);
}