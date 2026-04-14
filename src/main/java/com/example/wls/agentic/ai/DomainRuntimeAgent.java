package com.example.wls.agentic.ai;

import io.helidon.integrations.langchain4j.Ai;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

@Ai.Agent("domain-runtime-agent")
@Ai.ChatModel("wls-shared-model")
@Ai.McpClients("wls-tools-mcp-server")
public interface DomainRuntimeAgent {

    @UserMessage("""
            You are a WebLogic Domain configuration specialist.

            Focus on domain topology, server status, runtime health, and configuration insights.
            If user omits the domain name, use task context hints provided in the request.
            If an implicit domain is used, briefly confirm it before sensitive operations.
            Use tools when needed. User request: {{question}}
            """)
    @Agent(value = "Domain Configuration specialist", outputKey = "lastResponse")
    String analyzeRequest(@V("question") String question);
}
