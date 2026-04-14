# WebLogic Helidon Agentic POC

This project is a Helidon-based POC that mirrors the multi-agent declarative pattern from:

`helidon-labs/hols/langchain4j-agentic/code/final`

It is aligned to your diagram and uses:

- Main orchestrator: `WebLogicAgent`
- Specialists: `DomainViewAgent`, `PatchingAgent`, `AppManagementAgent`, `DiagnosticAgent`
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
  - `AppManagementAgent`
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

## TaskContext support

`/chat` now accepts an optional `taskContext` object and returns it in the response.

### Request example

```json
{
  "message": "Restart domain ACME_DEV safely",
  "summary": "",
  "taskContext": {
    "taskId": "task-1001",
    "conversationId": "conv-42",
    "userId": "ops.user",
    "intent": "DOMAIN_VIEW",
    "targetDomain": "ACME_DEV",
    "targetServers": "AdminServer,ms1,ms2",
    "environment": "dev",
    "riskLevel": "medium",
    "approvalRequired": false,
    "constraints": "maintenance-window=22:00-23:00",
    "memorySummary": ""
  }
}
```

### Backward compatibility

- Existing clients can continue sending only `{ "message", "summary" }`.
- If `taskContext` is omitted, the server initializes an empty context.
- The response still contains `message` and `summary`, and now also includes `taskContext` with updated `memorySummary`.
