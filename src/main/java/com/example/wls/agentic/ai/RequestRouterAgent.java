package com.example.wls.agentic.ai;

import io.helidon.integrations.langchain4j.Ai;

import dev.langchain4j.agentic.declarative.ActivationCondition;
import dev.langchain4j.agentic.declarative.ConditionalAgent;
import dev.langchain4j.service.V;

@Ai.Agent("request-router")
public interface RequestRouterAgent {

    @ConditionalAgent(subAgents = {
            DomainViewAgent.class,
            PatchingAgent.class,
            DiagnosticAgent.class
    })
    String askExpert(@V("question") String question);

    @ActivationCondition(DomainViewAgent.class)
    static boolean activateDomainView(@V("intent") RequestIntent intent) {
        return intent == RequestIntent.DOMAIN_VIEW;
    }

    @ActivationCondition(PatchingAgent.class)
    static boolean activatePatching(@V("intent") RequestIntent intent) {
        return intent == RequestIntent.PATCHING;
    }

    @ActivationCondition(DiagnosticAgent.class)
    static boolean activateDiagnostic(@V("intent") RequestIntent intent) {
        return intent == RequestIntent.DIAGNOSTIC_TROUBLESHOOTING;
    }
}
