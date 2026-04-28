package com.example.wls.agentic.ai;

import io.helidon.integrations.langchain4j.Ai;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

@Ai.Agent("request-classifier")
@Ai.ChatModel("wls-shared-model")
public interface RequestClassifierAgent {

    @UserMessage("""
            Analyze the following user request about WebLogic.
            Categorize it as exactly one of:
            - DOMAIN_CONFIGURATION: domain overview, topology, configuration insights, managed domain inventory, configuration changes/guidance (but not runtime control like start/stop)
            - DOMAIN_RUNTIME: only server runtime control like start/stop servers (do NOT include overview or config)
            - PATCHING: patching, OPatch, recommended/pending patches, job status/tracking
            - APP_MANAGEMENT: application deployment, management, updates
            - DIAGNOSTIC_TROUBLESHOOTING: diagnostics, troubleshooting, health checks (non-operational)
            - GENERAL_ASSISTANCE: anything else, like general questions or out-of-scope

            Examples:
            - "Show domain overview" -> DOMAIN_CONFIGURATION
            - "List managed domains" -> DOMAIN_CONFIGURATION
            - "Start server in domain" -> DOMAIN_RUNTIME
            - "Apply patches" -> PATCHING
            - "Troubleshoot error" -> DIAGNOSTIC_TROUBLESHOOTING
            - "What is WebLogic?" -> GENERAL_ASSISTANCE

            Reply with only one category token and nothing else.
            User request: '{{question}}'
            """)
    @Agent(value = "Classify WebLogic request", outputKey = "intent")
    RequestIntent classify(@V("question") String question);
}
