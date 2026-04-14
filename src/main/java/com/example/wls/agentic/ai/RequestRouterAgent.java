package com.example.wls.agentic.ai;

import io.helidon.integrations.langchain4j.Ai;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.langchain4j.agentic.declarative.ActivationCondition;
import dev.langchain4j.agentic.declarative.ConditionalAgent;
import dev.langchain4j.service.V;

@Ai.Agent("request-router")
public interface RequestRouterAgent {

    Logger LOGGER = Logger.getLogger(RequestRouterAgent.class.getName());

    @ConditionalAgent(subAgents = {
            DomainRuntimeAgent.class,
            PatchingAgent.class,
            AppManagementAgent.class,
            DiagnosticAgent.class,
            FeatureDisabledAgent.class
    })
    String askExpert(@V("question") String question);

    @ActivationCondition(DomainRuntimeAgent.class)
    static boolean activateDomainView(@V("intent") RequestIntent intent) {
        boolean selected = intent == RequestIntent.DOMAIN_VIEW && AgentFeatureFlags.isEnabled(intent);
        logSelection("DomainRuntimeAgent", intent, selected);
        return selected;
    }

    @ActivationCondition(PatchingAgent.class)
    static boolean activatePatching(@V("intent") RequestIntent intent) {
        boolean selected = intent == RequestIntent.PATCHING && AgentFeatureFlags.isEnabled(intent);
        logSelection("PatchingAgent", intent, selected);
        return selected;
    }

    @ActivationCondition(DiagnosticAgent.class)
    static boolean activateDiagnostic(@V("intent") RequestIntent intent) {
        boolean selected = intent == RequestIntent.DIAGNOSTIC_TROUBLESHOOTING && AgentFeatureFlags.isEnabled(intent);
        logSelection("DiagnosticAgent", intent, selected);
        return selected;
    }

    @ActivationCondition(AppManagementAgent.class)
    static boolean activateAppManagement(@V("intent") RequestIntent intent) {
        boolean selected = intent == RequestIntent.APP_MANAGEMENT && AgentFeatureFlags.isEnabled(intent);
        logSelection("AppManagementAgent", intent, selected);
        return selected;
    }

    @ActivationCondition(FeatureDisabledAgent.class)
    static boolean activateDisabledFeatureResponder(@V("intent") RequestIntent intent) {
        boolean selected = intent != null && !AgentFeatureFlags.isEnabled(intent);
        logSelection("FeatureDisabledAgent", intent, selected);
        return selected;
    }

    private static void logSelection(String agentName, RequestIntent intent, boolean selected) {
        LOGGER.log(Level.FINEST,
                "Classifier intent={0}; evaluating agent={1}; selected={2}",
                new Object[]{intent, agentName, selected});
    }
}
