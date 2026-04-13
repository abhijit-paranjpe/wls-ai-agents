package com.example.wls.agentic.ai;

import io.helidon.integrations.langchain4j.Ai;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

@Ai.Agent("summarizer")
@Ai.ChatModel("wls-shared-model")
public interface SummarizerAgent {

    @SystemMessage("""
            You are a conversation summarizer for a WebLogic agentic assistant.
            Keep a concise factual summary of the user's objective, environment details, and latest guidance.
            """)
    @UserMessage("""
            Previous Summary:
            {{previousSummary}}

            Last User Message:
            {{question}}

            Last AI Response:
            {{lastResponse}}
            """)
    @Agent(value = "WebLogic summarizer", outputKey = "nextSummary")
    String chat(@V("previousSummary") String previousSummary,
                @V("question") String question,
                @V("lastResponse") String lastResponse);
}
