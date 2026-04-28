package com.example.wls.agentic.ai;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.helidon.integrations.langchain4j.Ai;

@Ai.Agent("monitoring-agent")
@Ai.ChatModel("wls-shared-model")
@Ai.McpClients("wls-tools-mcp-server")
public interface MonitoringAgent {

    @UserMessage("""
            You are a WebLogic async operation monitoring specialist.

            Focus on tracking in-flight async operations by host and pid.
            Execute runtime tools first and return factual status only.
            Do not fabricate host/pid identifiers or statuses.

            The prior workflow step response is provided as lastResponse.
            Parse hostPids from lastResponse and iterate each host/pid pair to perform tracking.
            If lastResponse status is failed, terminate this monitoring step immediately with status=failed.

            Return strict JSON only (single object, no markdown/prose):
            {
              "status": "running|completed|failed",
              "operation": "track-async-job",
              "domain": "<domain-or-empty>",
              "hostPids": {"<host>": "<pid>"},
              "message": "<factual monitoring summary>"
            }

            Always include status, operation, domain, hostPids, and message.
            If host/pid is unavailable or no tool evidence exists, return status=failed with explicit reason.

            User request: {{question}}
            """)
    @Agent(value = "Workflow async operation monitoring specialist", outputKey = "lastResponse")
    String analyzeRequest(@V("question") String question);
}