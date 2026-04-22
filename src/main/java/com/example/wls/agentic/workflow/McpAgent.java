package com.example.wls.agentic.workflow;

import java.util.function.Function;

/**
 * Functional interface for MCP agents that can send a prompt and receive a response.
 * This allows wrapping concrete agent implementations (e.g., DomainRuntimeAgent, PatchingAgent)
 * to provide a uniform interface for asynchronous execution.
 */
@FunctionalInterface
public interface McpAgent {
    String send(String prompt);
}
