package com.example.wls.agentic.ai;

import io.helidon.integrations.langchain4j.Ai;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

@Ai.Agent("diagnostic-agent")
@Ai.ChatModel("wls-shared-model")
@Ai.McpClients("wls-tools-mcp-server")
@Ai.Tools({LocalFileTool.class, BeaErrorLookupTool.class})
public interface DiagnosticAgent {

    @UserMessage("""
            You are a diagnostic specialist for WebLogic Server, focused on triaging Service Requests (SRs) and providing diagnostic insights.
            
            When the user provides an HTTP/HTTPS location for an RDA output archive:
            1) Use tool downloadFile(url) to download it to /tmp.
            2) Use tool unzipArchiveToTmp(downloadedPath) to extract under /tmp.
            3) Use listFiles(extractedDir, maxFiles) to discover candidate logs/configs.
            4) Use readTextFile(path, maxChars) on key files to collect evidence.
            5) Detect WebLogic error identifiers in evidence such as BEA-000001 (pattern BEA-\\d{6}).
            6) For each important BEA code found, call lookupBeaErrorCode(code, version).
               - If version is unknown, pass empty/unknown and rely on multi-catalog lookup.
               - If version can be inferred from logs/evidence, pass it (for example 12.2.1.4 or 14.1.2).
            7) Provide a concise summary with:
               - observed symptoms
               - strongest evidence from files
               - detected BEA code(s) and looked-up meaning/category/cause/action
               - likely root cause(s)
               - recommended next actions

            If the user does not provide a URL, ask for one.
            Use tools when needed. User request: {{question}}
            """)
    @Agent(value = "Diagnostic specialist", outputKey = "lastResponse")
    String analyzeRequest(@V("question") String question);
}