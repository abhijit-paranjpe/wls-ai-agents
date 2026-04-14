# WebLogic Agentic Assistant – Design Document (POC)

## 1) Purpose and Scope

This document describes the current Proof of Concept (POC) design of the **WebLogic Agentic Assistant** implemented in this repository.

The assistant is designed to:
- Interpret WebLogic operational requests from a chat endpoint
- Route each request to the right specialist capability
- Use MCP-backed tools for runtime/domain/app operations
- Preserve concise conversational context across turns

The current implementation is built on Helidon SE + LangChain4j agentic annotations.

## 2) High-Level Architecture

Core orchestration is implemented by `WebLogicAgent` as a `@SequenceAgent` pipeline:

1. `RequestClassifierAgent` → classifies intent
2. `RequestRouterAgent` → conditionally activates specialist agent
3. `SummarizerAgent` → updates compact conversation summary

The final response object is created via `@Output` and returned as `AgentResponse`.

### Main participating components

- **REST entrypoint**: `ChatBotEndpoint`
- **Orchestrator**: `WebLogicAgent`
- **Classifier**: `RequestClassifierAgent`
- **Conditional router**: `RequestRouterAgent`
- **Specialists**:
  - `DomainRuntimeAgent`
  - `PatchingAgent`
  - `DiagnosticAgent`
  - `AppManagementAgent`
- **Memory/context carrier**: `TaskContext`
- **Conversation compressor**: `SummarizerAgent`

## 3) End-to-End Request Lifecycle

1. Client calls `POST /chat` with message + optional summary + optional `taskContext`.
2. `ChatBotEndpoint` parses payload, normalizes missing parts, and enriches context by detecting domain/server/host mentions from user text.
3. Endpoint applies implicit-domain reuse guidance when appropriate.
4. Endpoint compacts context + summary (truncation guards) and invokes `WebLogicAgent.chat(...)`.
5. `RequestClassifierAgent` maps request to one of:
   - `DOMAIN_VIEW`
   - `PATCHING`
   - `APP_MANAGEMENT`
   - `DIAGNOSTIC_TROUBLESHOOTING`
6. `RequestRouterAgent` chooses exactly one specialist through `@ActivationCondition`.
7. Specialist agent responds (tool usage via MCP when needed).
8. `SummarizerAgent` produces `nextSummary`.
9. `WebLogicAgent.createResponse(...)` returns `AgentResponse(lastResponse, nextSummary, taskContext)`.

## 4) Agent Responsibilities and Boundaries

### `RequestClassifierAgent`
- Performs narrow intent classification only
- Outputs tokenized `RequestIntent`

### `RequestRouterAgent`
- Encodes deterministic routing policy
- Keeps specialist activation explicit and testable

### Specialist agents
- `DomainRuntimeAgent`: topology, server status, runtime health, config insights
- `PatchingAgent`: patch advisory/planning/execution guidance
- `DiagnosticAgent`: SR triage and troubleshooting guidance
- `AppManagementAgent`: app lifecycle operations (list, status, deploy, redeploy, undeploy)

Specialists share common patterns:
- `@Ai.ChatModel("wls-shared-model")`
- MCP client access for WebLogic tools
- `outputKey = "lastResponse"`

### `SummarizerAgent`
- Produces concise continuity summary (`nextSummary`)
- Reduces prompt bloat while preserving context signal

## 5) Context and Memory Model

`TaskContext` carries operational state across turns, including:
- `targetDomain`, `targetServers`, `targetHosts`
- `hostPids`
- `riskLevel`, `approvalRequired`, `confirmTargetOnImplicitReuse`
- `constraints`, `memorySummary`

`ChatBotEndpoint` is responsible for:
- Parsing malformed/partial payloads defensively
- Inferring context hints from user language
- Preserving safe defaults
- Truncating oversized context/summary fields

This keeps conversation continuity robust while controlling token growth.

## 6) MCP / Tool Integration Model

Specialist agents are annotated with MCP client bindings and can call operational tools through the configured MCP server (for example WebLogic runtime, app lifecycle, and patching operations).

Design intent:
- Natural-language reasoning in agents
- Deterministic execution through tools
- Centralized connectivity and policy via MCP configuration

## 7) Design Rationale: Why Multi-Agent in this POC

The multi-agent split was chosen deliberately over a single monolithic agent.

1. **Separation of concerns**  
   Each agent has a tight, domain-specific responsibility.

2. **Higher specialist accuracy**  
   Focused prompts reduce cross-domain confusion and improve answer relevance.

3. **Deterministic routing**  
   Classification + activation conditions create predictable behavior.

4. **Maintainability**  
   Updating app-management logic does not require rewriting patching/diagnostic prompts.

5. **Extensibility**  
   New capabilities are added by introducing a new intent + specialist + activation rule.

6. **Observability and debugging**  
   Failures can be localized to classifier, router, specialist, or summarizer stages.

7. **Operational safety**  
   Domain reuse and confirmation behavior can be consistently enforced via orchestration and context policy.

8. **Context efficiency**  
   Summarization keeps memory compact without forcing every specialist prompt to carry full history.

## 8) Trade-offs and Current Limitations

- More components than a single-agent design (higher orchestration complexity)
- Quality depends on classifier correctness
- Prompt/routing policy needs ongoing tuning as request diversity increases
- Current POC may still need stronger guardrails around risky action confirmation semantics

## 9) Next Evolution Steps

- Add intent confidence scoring and fallback clarification flow
- Add structured audit trail per stage (classifier, router, specialist, tools)
- Expand policy-driven safety checks for production operations
- Introduce per-specialist evaluation harness and regression tests
- Keep architecture diagram (`docs/wls-agent-multi-agent-architecture.drawio`) synchronized with code changes
