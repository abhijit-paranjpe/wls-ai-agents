package com.example.wls.agentic.ai;

import com.example.wls.agentic.dto.AgentResponse;
import com.example.wls.agentic.dto.TaskContext;
import io.helidon.integrations.langchain4j.Ai;

import dev.langchain4j.agentic.declarative.Output;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.V;

@Ai.Agent("wls-expert")
public interface WebLogicAgent {

    @SequenceAgent(outputKey = "jsonResponse", subAgents = {
            RequestClassifierAgent.class,
            RequestRouterAgent.class,
            SummarizerAgent.class
    })
    @SystemMessage("""
            You are a WebLogic expert assistant.
            
            Help with domain management, patching, and diagnostic/troubleshooting.

            Use the following conversation summary to keep context and maintain continuity:
            {{previousSummary}}

            Use this structured task context to tailor responses and decisions:
            {{taskContext}}

            Reuse targetDomain from task context when the user asks follow-up operations without explicitly naming a domain.
            If confirmTargetOnImplicitReuse is true, explicitly confirm the inferred domain before risky operations.
            """)
    AgentResponse chat(@V("question") String question,
                       @V("previousSummary") String previousSummary,
                       @V("taskContext") String taskContext,
                       @V("taskContextObject") TaskContext taskContextObject);

    @Output
    static AgentResponse createResponse(@V("lastResponse") String lastResponse,
                                        @V("nextSummary") String nextSummary,
                                        @V("taskContextObject") TaskContext taskContextObject) {
        TaskContext finalContext = taskContextObject == null
                ? TaskContext.empty().withMemorySummary(nextSummary)
                : taskContextObject.withMemorySummary(nextSummary);

        return new AgentResponse(lastResponse, nextSummary, finalContext);
    }
}
