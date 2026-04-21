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
            - DOMAIN_CONFIGURATION
            - DOMAIN_RUNTIME
            - WORKFLOW_REQUEST
            - PATCHING
            - APP_MANAGEMENT
            - DIAGNOSTIC_TROUBLESHOOTING
            - GENERAL_ASSISTANCE

            DOMAIN_CONFIGURATION is for domain overview, topology, configuration insights,
            configuration updates/changes, server and cluster inventory, and general domain status summaries.

            DOMAIN_RUNTIME is only for server runtime control actions such as:
            - start servers
            - stop servers

            Do NOT use DOMAIN_RUNTIME for:
            - domain overview or topology
            - configuration changes or configuration guidance
            - start/stop/restart/shutdown domain
            - restart/shutdown servers

            WORKFLOW_REQUEST is only for patch-application workflows that the user wants to execute.
            Examples include:
            - /apply-patches
            - can you apply latest patches to the domain
            - please apply latest patches to domain
            - apply following patches to the domain
            - follow-up continuation messages that indicate the user already confirmed
              a pending patch-application workflow
            - follow-up continuation messages that supply a missing target domain
              for a pending patch-application workflow

            Do NOT use WORKFLOW_REQUEST for informational patching requests such as:
            - is my domain on latest patches?
            - list latest patches
            - show domain patch status
            - show pending patches

            If the request says the user answered yes/confirmed a pending PATCHING or
            PATCHING_WORKFLOW follow-up, classify it as WORKFLOW_REQUEST.

            If the request says the user supplied the missing domain for a pending
            PATCHING or PATCHING_WORKFLOW follow-up, classify it as WORKFLOW_REQUEST.

            Use PATCHING for informational patch inventory, advisory, status, and job-tracking requests.

            Use GENERAL_ASSISTANCE for greetings, small talk, or broad/non-operational requests
            that do not clearly map to a specific WebLogic operational workflow.

            DIAGNOSTIC_TROUBLESHOOTING is only for diagnostic analysis and SR-style troubleshooting
            (evidence gathering, triage, likely root cause, and remediation guidance).
            Do NOT classify operational actions as DIAGNOSTIC_TROUBLESHOOTING, including:
            - patching operations
            - async/background job tracking, PID/job status checks
            - start/stop domain or servers

            Reply with only one category token and nothing else.
            User request: '{{question}}'
            """)
    @Agent(value = "Classify WebLogic request", outputKey = "intent")
    RequestIntent classify(@V("question") String question);
}
