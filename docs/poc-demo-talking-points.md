# Talking Points for WebLogic Agentic Assistant POC Demo

## 1) Drivers for the POC
- **Operational pain today**: WebLogic operations often involve manual scripts, runtime checks, and troubleshooting across multiple environments.
- **POC goal**: Validate a conversational assistant that can interpret ops requests and route to the right specialized capability.
- **Key value drivers**:
  - Faster operator workflows (status, diagnostics, patching, app lifecycle)
  - Better safety through structured context (risk, approvals, constraints)
  - Maintainable architecture via modular multi-agent design
  - Reusable integration model through MCP-backed tools
- **Scope**: This POC emphasizes orchestration and routing shape, with extension points for production-grade tools and guardrails.

## 2) Architecture Overview

### End-to-end pipeline
`POST /chat` -> `WebLogicAgent` sequence -> classifier -> router -> specialist -> summarizer -> response

1. **RequestClassifierAgent**
   - Maps user input to one intent:
     - `DOMAIN_VIEW`
     - `PATCHING`
     - `APP_MANAGEMENT`
     - `DIAGNOSTIC_TROUBLESHOOTING`
2. **RequestRouterAgent**
   - Uses deterministic activation conditions to choose exactly one specialist.
3. **Specialist agents**
   - `DomainRuntimeAgent`: domain/server/runtime view
   - `PatchingAgent`: patch planning/advice/execution guidance
   - `AppManagementAgent`: app deploy/redeploy/start/status/undeploy
   - `DiagnosticAgent`: troubleshooting and SR triage assistance
4. **SummarizerAgent**
   - Produces compact `nextSummary` for context continuity and token control.

### Supporting components
- **REST entrypoint**: `ChatBotEndpoint`
- **Context carrier**: `TaskContext`
- **Response DTO**: `AgentResponse(lastResponse, nextSummary, taskContext)`
- **Model strategy**: Shared model (`wls-shared-model`) across all agents
- **Tool access**: MCP integration (`wls-tools-mcp-server`) for deterministic operational actions

## 3) Demo Flow Talking Points
- Start with a runtime question:
  - “Show health/status for ACME_DEV”
  - Explain classifier -> router -> `DomainRuntimeAgent` path
- Continue with a workflow step:
  - “Now deploy myapp.war to ms1”
  - Show context reuse from `TaskContext` and handoff to `AppManagementAgent`
- Show continuity:
  - `SummarizerAgent` updates `nextSummary`
  - Response carries updated context for next turn
- Highlight control points:
  - Risk level/approval semantics
  - Implicit target reuse safeguards

## 4) Handling Context (Current + Next)

### Current behavior
- `/chat` accepts optional `taskContext` and `summary`.
- Endpoint normalizes missing fields and enriches context hints (domain/server/host mentions).
- Truncation guards prevent oversized summary/context payloads.

### Next improvements
- Add confidence scoring on classification + fallback clarification when confidence is low.
- Strengthen context policy for high-risk actions (explicit approval and confirmation flow).
- Add durable memory strategy (session/user scoped) for longer-running operational threads.

## 5) Workflow Evolution Roadmap
- **Structured workflows**: Move from single-turn intent routing to multi-step operational playbooks (diagnose -> patch -> verify).
- **Safety + compliance**: Stage-level audit trail (classifier, router, specialist, tool calls), approval checkpoints, and rollback guidance.
- **Evaluation harness**: Regression tests per specialist and routing correctness tests.
- **Extensibility**: Add new specialist by introducing intent + activation rule + focused prompt/toolset.

## 6) Why This POC Design Matters
- Better separation of concerns than a monolithic agent.
- More predictable behavior via deterministic routing.
- Easier troubleshooting by localizing failures to pipeline stages.
- Better long-term scalability for enterprise WebLogic operations.

## 7) Suggested Close / Next Steps for Stakeholders
- Confirm priority production workflows to harden first (e.g., app deploy or incident triage).
- Define safety policy requirements (approval gates, blast-radius controls, audit depth).
- Stand up production-like MCP tool endpoints and begin integration testing.
- Keep architecture diagram and prompt/routing rules synchronized with implementation changes.
