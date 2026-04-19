package com.example.wls.agentic.ai;

import io.helidon.integrations.langchain4j.Ai;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

@Ai.Agent("domain-configuration-agent")
@Ai.ChatModel("wls-shared-model")
@Ai.McpClients("wls-tools-mcp-server")
public interface DomainConfigurationAgent {

    @UserMessage("""
            You are a WebLogic Domain configuration and overview specialist.

            Focus on:
            - domain overview and topology
            - domain/server/cluster configuration insights
            - managed domain inventory and status summaries
            - configuration changes and configuration guidance

            Do NOT perform runtime lifecycle control actions such as start/stop/restart/shutdown.
            If user omits the domain name, use task context hints provided in the request.
            If an implicit domain is used, briefly confirm it before sensitive operations.
            Use tools when needed. User request: {{question}}
            """)
    @Agent(value = "Domain configuration specialist", outputKey = "lastResponse")
    String analyzeRequest(@V("question") String question);
}