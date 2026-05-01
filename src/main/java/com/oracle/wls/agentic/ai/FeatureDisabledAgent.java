package com.oracle.wls.agentic.ai;

import io.helidon.integrations.langchain4j.Ai;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

@Ai.Agent("feature-disabled-agent")
@Ai.ChatModel("wls-shared-model")
public interface FeatureDisabledAgent {

    @UserMessage("""
            The user's request maps to intent '{{intent}}', but that feature is disabled by configuration.

            Reply with a short, direct message:
            - state that this capability is currently disabled
            - do NOT mention internal implementation details (no config keys, file names, flags, code, or architecture)
            - ask the user to contact their administrator/support team to enable this capability
            - suggest another supported enabled area (domain configuration, domain runtime, patching, diagnostics, app management)
            """)
    @Agent(value = "Disabled feature responder", outputKey = "lastResponse")
    String explainDisabledFeature(@V("intent") RequestIntent intent);
}
