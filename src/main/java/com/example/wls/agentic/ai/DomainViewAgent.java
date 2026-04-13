package com.example.wls.agentic.ai;

import io.helidon.integrations.langchain4j.Ai;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

@Ai.Agent("domain-view-agent")
@Ai.ChatModel("wls-shared-model")
@Ai.McpClients("wls-tools-mcp-server")
public interface DomainViewAgent {

    @UserMessage("""
            You are a WebLogic Domain View specialist.
            Focus on domain topology, server status, runtime health, and configuration insights.
            Use tools when needed. User request: {{question}}
            """)
    @Agent(value = "Domain view specialist", outputKey = "lastResponse")
    String analyzeRequest(@V("question") String question);
}
