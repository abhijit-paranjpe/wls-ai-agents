package com.example.wls.agentic.rest;

import com.example.wls.agentic.ai.WebLogicAgent;
import com.example.wls.agentic.dto.AgentResponse;
import io.helidon.http.Http;
import io.helidon.service.registry.Service;
import io.helidon.webserver.http.RestServer;

import static io.helidon.common.media.type.MediaTypes.APPLICATION_JSON_VALUE;

@RestServer.Endpoint
@Http.Path
@Service.Singleton
public class ChatBotEndpoint {

    private final WebLogicAgent agent;

    @Service.Inject
    public ChatBotEndpoint(WebLogicAgent agent) {
        this.agent = agent;
    }

    @Http.POST
    @Http.Path("/chat")
    @Http.Produces(APPLICATION_JSON_VALUE)
    public AgentResponse chatWithAssistant(@Http.Entity AgentResponse msg) {
        return agent.chat(msg.message(), msg.summary());
    }
}
