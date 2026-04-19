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
            You are a WebLogic server runtime control specialist.

            Focus only on server runtime control actions:
            - start servers
            - stop servers

            Do NOT handle domain overview, configuration change requests, domain lifecycle actions,
            or server restart/shutdown requests.
            If user omits the domain name, use task context hints provided in the request.
            If an implicit domain is used, briefly confirm it before sensitive operations.
            For server start/stop operations, execute tools first and report only factual results.
            Do NOT invent or assume job IDs, tracking IDs, PIDs, or statuses.
            If tool output does not provide a tracking identifier, explicitly say no tracking ID was returned.
            Use tools when needed. User request: {{question}}
            """)
    @Agent(value = "Domain runtime server control specialist", outputKey = "lastResponse")
    String analyzeRequest(@V("question") String question);
}
