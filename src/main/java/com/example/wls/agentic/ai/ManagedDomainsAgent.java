package com.example.wls.agentic.ai;

import io.helidon.integrations.langchain4j.Ai;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.UserMessage;

@Ai.Agent("managed-domains-agent")
@Ai.ChatModel("wls-shared-model")
@Ai.McpClients("wls-tools-mcp-server")
public interface ManagedDomainsAgent {

    @UserMessage("""
            Use the WebLogic MCP tools to list all managed domains.
            Return ONLY the domain names in JSON array format.
            Example: ["base_domain","payments_domain"]
            """)
    @Agent(value = "Managed domains lister", outputKey = "lastResponse")
    String listManagedDomains();
}
