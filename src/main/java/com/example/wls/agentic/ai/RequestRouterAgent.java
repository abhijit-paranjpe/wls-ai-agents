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
            DomainConfigurationAgent.class,
            DomainRuntimeAgent.class,
            PatchingAgent.class,
            AppManagementAgent.class,
            DiagnosticAgent.class,
            GeneralAssistantAgent.class,
            FeatureDisabledAgent.class
    })
    String askExpert(@V("question") String question);

    @ActivationCondition(DomainConfigurationAgent.class)
    static boolean activateDomainConfiguration(@V("intent") RequestIntent intent,
                                               @V("question") String question) {
        boolean selected = AgentFeatureFlags.isEnabled(RequestIntent.DOMAIN_CONFIGURATION)
                && intent == RequestIntent.DOMAIN_CONFIGURATION
                && !looksLikeServerLifecycleRequest(question);
        logSelection("DomainConfigurationAgent", intent, selected);
        return selected;
    }

    @ActivationCondition(DomainRuntimeAgent.class)
    static boolean activateDomainRuntime(@V("intent") RequestIntent intent,
                                         @V("question") String question) {
        boolean selected = AgentFeatureFlags.isEnabled(RequestIntent.DOMAIN_RUNTIME)
                && (intent == RequestIntent.DOMAIN_RUNTIME || looksLikeServerLifecycleRequest(question));
        logSelection("DomainRuntimeAgent", intent, selected);
        return selected;
    }

    @ActivationCondition(PatchingAgent.class)
    static boolean activatePatching(@V("intent") RequestIntent intent,
                                    @V("question") String question) {
        boolean selected = AgentFeatureFlags.isEnabled(RequestIntent.PATCHING)
                && (intent == RequestIntent.PATCHING || looksLikePatchingOrJobTrackingRequest(question));
        logSelection("PatchingAgent", intent, selected);
        return selected;
    }

    @ActivationCondition(DiagnosticAgent.class)
    static boolean activateDiagnostic(@V("intent") RequestIntent intent,
                                      @V("question") String question) {
        boolean selected = intent == RequestIntent.DIAGNOSTIC_TROUBLESHOOTING
                && AgentFeatureFlags.isEnabled(intent)
                && !looksLikeOperationalRequest(question);
        logSelection("DiagnosticAgent", intent, selected);
        return selected;
    }

    @ActivationCondition(AppManagementAgent.class)
    static boolean activateAppManagement(@V("intent") RequestIntent intent) {
        boolean selected = intent == RequestIntent.APP_MANAGEMENT && AgentFeatureFlags.isEnabled(intent);
        logSelection("AppManagementAgent", intent, selected);
        return selected;
    }

    @ActivationCondition(GeneralAssistantAgent.class)
    static boolean activateGeneralAssistance(@V("intent") RequestIntent intent) {
        boolean selected = intent == RequestIntent.GENERAL_ASSISTANCE;
        logSelection("GeneralAssistantAgent", intent, selected);
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

    private static boolean looksLikeOperationalRequest(String question) {
        return looksLikeServerLifecycleRequest(question) || looksLikePatchingOrJobTrackingRequest(question);
    }

    private static boolean looksLikeServerLifecycleRequest(String question) {
        if (question == null) {
            return false;
        }
        String q = question.toLowerCase();
        return q.contains("start server")
                || q.contains("stop server")
                || q.contains("start servers")
                || q.contains("stop servers");
    }

    private static boolean looksLikePatchingOrJobTrackingRequest(String question) {
        if (question == null) {
            return false;
        }
        String q = question.toLowerCase();
        return q.contains("patch")
                || q.contains("opatch")
                || q.contains("recommended patches")
                || q.contains("pending patches")
                || q.contains("job status")
                || q.contains("track job")
                || q.contains("track async")
                || q.contains("background job")
                || q.contains(" pid ")
                || q.endsWith(" pid")
                || q.contains("async job");
    }
}
