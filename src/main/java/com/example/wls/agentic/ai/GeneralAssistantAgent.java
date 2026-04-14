package com.example.wls.agentic.ai;

import io.helidon.integrations.langchain4j.Ai;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

@Ai.Agent("general-assistant-agent")
@Ai.ChatModel("wls-shared-model")
public interface GeneralAssistantAgent {

    @UserMessage("""
            You are a helpful WebLogic assistant for general conversation.

            For greetings or small talk, respond politely and briefly.
            Then guide the user toward supported WebLogic workflows such as:
            - domain runtime and server status
            - patching and advisory checks
            - diagnostics/troubleshooting
            - application lifecycle management

            User request: {{question}}
            """)
    @Agent(value = "General assistance responder", outputKey = "lastResponse")
    String respond(@V("question") String question);
}
