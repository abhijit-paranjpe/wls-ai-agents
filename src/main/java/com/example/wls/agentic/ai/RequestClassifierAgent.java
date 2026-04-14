package com.example.wls.agentic.ai;

import io.helidon.integrations.langchain4j.Ai;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

@Ai.Agent("request-classifier")
@Ai.ChatModel("wls-shared-model")
public interface RequestClassifierAgent {

    @UserMessage("""
            Analyze the following user request about WebLogic.
            Categorize it as exactly one of:
            - DOMAIN_VIEW
            - PATCHING
            - APP_MANAGEMENT
            - Diagnostic/Troubleshooting

            Reply with only one category token and nothing else.
            User request: '{{question}}'
            """)
    @Agent(value = "Classify WebLogic request", outputKey = "intent")
    RequestIntent classify(@V("question") String question);
}
