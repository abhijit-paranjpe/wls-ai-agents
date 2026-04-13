# WebLogic Helidon Agentic POC

This project is a Helidon-based POC that mirrors the multi-agent declarative pattern from:

`helidon-labs/hols/langchain4j-agentic/code/final`

It is aligned to your diagram and uses:

- Main orchestrator: `WebLogicAgent`
- Specialists: `DomainViewAgent`, `PatchingAgent`, `DiagnosticAgent`
- Classifier + conditional router + summarizer flow
- Single shared model for all agents (`wls-shared-model`)
- MCP server integration via `@Ai.McpClients("wls-tools-mcp-server")`

## Package layout

- `com.example.wls.agentic.ai`
  - `WebLogicAgent`
  - `RequestClassifierAgent`
  - `RequestRouterAgent`
  - `DomainViewAgent`
  - `PatchingAgent`
  - `DiagnosticAgent`
  - `SummarizerAgent`
  - `RequestIntent`
- `com.example.wls.agentic.dto`
  - `AgentResponse`
- `com.example.wls.agentic.rest`
  - `ChatBotEndpoint`

## Configuration

See `src/main/resources/application.yaml`:

- `langchain4j.models.wls-shared-model` → shared model for all agents
- `langchain4j.mcp-clients.wls-tools-mcp-server.uri` → MCP server endpoint

Environment variables:

- `OCI_API_KEY`
- `OCI_OPENAI_BASE_URL`
- `WLS_AGENT_MODEL` (optional override)
- `WLS_MCP_URI` (optional override)

## Notes

- This is a scaffold POC focused on agent configuration and orchestration shape.
- `ApplicationMain` is intentionally minimal; wire full Helidon bootstrap/runtime as needed in your environment.
- If you want, next step is adding concrete MCP tools and prompt hardening per agent.
