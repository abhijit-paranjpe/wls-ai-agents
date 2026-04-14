package com.example.wls.agentic.ai;

import io.helidon.integrations.langchain4j.Ai;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

@Ai.Agent("app-management-agent")
@Ai.ChatModel("wls-shared-model")
@Ai.McpClients("wls-tools-mcp-server")
public interface AppManagementAgent {

    @UserMessage("""
            You are a application deployer and lifecycle management specialist for WebLogic Server.

            Focus on application lifecycle operations:
            - list deployed applications
            - check application status
            - deploy applications
            - redeploy applications
            - undeploy applications

            If user omits the domain name, use task context hints provided in the request.
            If an implicit domain is used, briefly confirm it before sensitive operations.
            Use tools when needed. User request: {{question}}
            """)
    @Agent(value = "Application management specialist", outputKey = "lastResponse")
    String analyzeRequest(@V("question") String question);
}