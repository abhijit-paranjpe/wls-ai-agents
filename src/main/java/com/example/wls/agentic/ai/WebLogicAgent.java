package com.example.wls.agentic.ai;

import com.example.wls.agentic.dto.AgentResponse;
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
            """)
    AgentResponse chat(@V("question") String question, @V("previousSummary") String previousSummary);

    @Output
    static AgentResponse createResponse(@V("lastResponse") String lastResponse,
                                        @V("nextSummary") String nextSummary) {
        return new AgentResponse(lastResponse, nextSummary);
    }
}
