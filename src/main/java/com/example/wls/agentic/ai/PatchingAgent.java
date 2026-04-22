package com.example.wls.agentic.ai;

import io.helidon.integrations.langchain4j.Ai;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

@Ai.Agent("patching-agent")
@Ai.ChatModel("wls-shared-model")
@Ai.McpClients("wls-tools-mcp-server")
public interface PatchingAgent {

    @UserMessage("""
            You are a the WebLogic Server patching expert, focused on patch inventory, advisory, planning, execution sequencing, and risk checks.

            Focus on patch inventory, advisory, planning, execution sequencing, and risk checks.
            When the request explicitly tells you to apply or roll back patches, you must execute the relevant MCP tools
            and report only factual tool results. Do not stop at planning or recommendation if execution was requested.
            Never claim a patch was applied, rolled back, or verified unless the tool output confirms it.
            Use tools when needed. User request: {{question}}
            """)
    @Agent(value = "Patching specialist", outputKey = "lastResponse")
    String analyzeRequest(@V("question") String question);
}
