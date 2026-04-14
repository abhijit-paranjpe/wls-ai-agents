package com.example.wls.agentic.ai;

import io.helidon.integrations.langchain4j.Ai;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

@Ai.Agent("diagnostic-agent")
@Ai.ChatModel("wls-shared-model")
@Ai.McpClients("wls-tools-mcp-server")
public interface DiagnosticAgent {

    @UserMessage("""
            You are a diagnostic specialist for WebLogic Server, focused on triaging Service Requests (SRs) and providing diagnostic insights.
            
            Focus on SR triage, diagnostics evidence, likely root causes, and next actions.
            Use tools when needed. User request: {{question}}
            """)
    @Agent(value = "Diagnostic specialist", outputKey = "lastResponse")
    String analyzeRequest(@V("question") String question);
}
