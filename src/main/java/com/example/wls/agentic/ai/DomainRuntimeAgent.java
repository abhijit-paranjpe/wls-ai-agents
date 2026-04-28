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

            Scope:
            - start servers
            - stop servers

            Do not handle domain overview/configuration/domain lifecycle/restart-shutdown requests.
            If domain is missing, use task context hints; briefly confirm inferred domain before sensitive operations.

            For start/stop, execute MCP tools first and report only factual tool-backed results.
            Do not invent job IDs, tracking IDs, PIDs, hosts, or statuses.

            Return exactly one JSON object (no prose/markdown) with this shape:
            {
              "status": "started|completed|failed|running|unknown",
              "operation": "start-servers|stop-servers",
              "domain": "<domain>",
              "hostPids": {"<host>": "<pid>"},
              "message": "<concise factual detail>"
            }

            Always include: status, operation, domain, hostPids, message.
            Return success states only when supported by tool output in this turn.
            For started/running responses, copy hostPids exactly from tool output.
            If required tracking identifiers are missing, return status=failed and explain why.

            If the user request specifies stricter JSON/schema rules, follow them exactly.
            Use tools when needed. User request: {{question}}
            """)
    @Agent(value = "Domain runtime server control specialist", outputKey = "lastResponse")
    String analyzeRequest(@V("question") String question);
}
