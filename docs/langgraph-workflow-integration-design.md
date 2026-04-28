# LangGraph Workflow Integration Design

## 1) Purpose

Define how to introduce **LangGraph for workflow orchestration only** in this project, while keeping existing Helidon + LangChain4j chat orchestration for non-workflow interactions.

This design targets patching workflow execution first, because that path has strict sequencing, state transitions, retries/timeouts, and failure semantics that are better modeled as a graph/state machine.

---

## 2) Scope

### In scope

- Use LangGraph-style orchestration for patching workflow execution stages:
  1. Stop servers
  2. Monitor stop
  3. Apply patches
  4. Monitor patch
  5. Start servers
  6. Monitor start
  7. Verify patch level
- Keep existing workflow persistence (`WorkflowStateStore`) as system of record.
- Keep existing REST endpoints and workflow APIs unchanged.
- Keep specialist agents (`DomainRuntimeAgent`, `PatchingAgent`, `MonitoringAgent`) reusable from workflow nodes through service adapters.

### Out of scope

- Replacing full top-level chat orchestration (`WebLogicAgent`, classifier/router/summarizer).
- Replacing conversation memory implementation.
- Large redesign of request routing for non-workflow intents.

---

## 3) Current State Summary

Current workflow execution uses declarative LangChain4j `@SequenceAgent` flow (`WorkflowExecutionSequenceAgent`) and shared response chaining through `lastResponse`.

Observed issues for workflow use cases:

- Implicit cross-step state coupling via free-form text/JSON parsing.
- Fragility when required tracking metadata (for example `hostPids`) is missing or malformed.
- Harder branching/retry/timeout semantics compared to explicit graph edges.

The project already has strong persisted workflow state support (`WorkflowRecord`, `WorkflowStepRecord`, `WorkflowStateStore`, `WorkflowStateMutationService`) that can anchor a graph-based execution engine.

---

## 4) Target Hybrid Architecture

### Keep unchanged

- `ChatBotEndpoint`
- `WebLogicAgent`
- `RequestClassifierAgent`
- `RequestRouterAgent`
- Existing specialist agent interfaces and MCP configuration
- Conversation memory stores (in-memory/Redis/Mongo)

### Introduce for workflows

- A workflow graph runner abstraction for patching workflow execution.
- Graph nodes that call Helidon-managed workflow services.
- Workflow services that invoke existing LangChain4j specialist agents.

### Key seam

- **Current**: `PatchingWorkflowCoordinator -> WorkflowExecutionSequenceAgent.run(...)`
- **Target**: `PatchingWorkflowCoordinator -> PatchingWorkflowGraphRunner.run(...)`

This minimizes surface-area change and keeps endpoints/state contracts stable.

---

## 5) Helidon Annotation Strategy

For new integration-layer components, use Helidon service annotations:

- `@Service.Singleton` for graph runner and workflow adapter services.
- `@Service.Inject` for constructor injection.

Existing agent interfaces keep current Helidon AI annotations:

- `@Ai.Agent(...)`
- `@Ai.ChatModel(...)`
- `@Ai.McpClients(...)`

### Design principle

LangGraph nodes should depend on **typed workflow services**, not directly on raw prompt contracts.

---

## 6) Proposed Components

## 6.1 Workflow Graph Runner

- `PatchingWorkflowGraphRunner`
  - Entry point for post-approval workflow execution.
  - Accepts `workflowId`, `domain`, and execution instruction/context.
  - Executes graph transitions and persists state updates through mutation service.

## 6.2 Workflow Adapter Services

- `WorkflowDomainRuntimeService`
  - Wraps `DomainRuntimeAgent` for stop/start operations.
  - Normalizes responses into typed DTOs.

- `WorkflowPatchingService`
  - Wraps `PatchingAgent` for patch apply and patch-level verification.

- `WorkflowMonitoringService`
  - Wraps `MonitoringAgent` for async monitoring/polling.

## 6.3 Typed Result DTOs (examples)

- `AsyncOperationResult` (status, message, hostPids, rawResponse)
- `MonitorOperationResult` (status: running|completed|failed, message, rawResponse)
- `PatchVerificationResult` (status, patchSummary, rawResponse)

These DTOs reduce dependence on direct free-form `lastResponse` chaining.

---

## 7) Workflow State and Memory Model

There are two separate state layers:

1. **In-run graph state** (ephemeral execution object)
   - current node/step
   - hostPids/tracking data
   - normalized result from previous node
   - attempt counts and timing metadata

2. **Persisted workflow state** (durable system of record)
   - `WorkflowRecord`
   - `WorkflowStepRecord`
   - `WorkflowStatus`
   - `failureReason`

### Persistence rule

Graph transitions must persist meaningful step updates via existing workflow mutation layer.

Recommended initial persistence API:

- `markStepInExecution(...)`
- `markStepCompleted(...)`
- `markStepFailedAndFailWorkflow(...)`

Optional future additions:

- workflow-level `IN_EXECUTION/COMPLETED` helpers
- checkpoint metadata fields
- monitor-attempt/timeout metadata

---

## 8) Node and Edge Design

## 8.1 Nodes

1. `StopServersNode`
2. `MonitorStopNode`
3. `ApplyPatchesNode`
4. `MonitorPatchNode`
5. `StartServersNode`
6. `MonitorStartNode`
7. `VerifyPatchLevelNode`
8. `FailWorkflowNode`
9. `CompleteWorkflowNode`

## 8.2 Transition semantics

- `completed` -> next workflow node
- `running` (monitor nodes) -> monitor node retry/loop edge (with timeout/attempt guard)
- `failed` -> fail workflow node
- malformed/missing required metadata (for example missing `hostPids` for async step) -> fail workflow node

## 8.3 Failure semantics

Any terminal node failure must:

1. mark current step failed
2. mark workflow `FAILED`
3. persist explicit normalized reason
4. stop downstream execution

---

## 9) How LangGraph Nodes Call LangChain4j Agents

Supported and recommended pattern:

- LangGraph node -> `Workflow*Service` (Helidon singleton)
- `Workflow*Service` -> corresponding LangChain4j agent (`DomainRuntimeAgent`, `PatchingAgent`, `MonitoringAgent`)

This preserves clean boundaries:

- graph = control flow/state transitions
- services = integration + normalization + validation
- agents = reasoning/tool invocation

---

## 10) Dependency Strategy (`pom.xml`)

Current dependencies already support Helidon + LangChain4j layers used by adapters/services.

For phase 1 design and boundary refactor, no immediate dependency removal is needed.

Because this repository is JVM/Helidon, final dependency choices depend on runtime decision:

- If LangGraph runs as an external service: add only client integration dependency if required.
- If using in-process Java orchestration equivalent: add library dependency (if selected) or keep internal runner abstraction first.

Decision can be deferred while implementing clean integration boundaries now.

---

## 11) Migration Plan

## Phase 1: Design + Abstractions

- Add design document (this doc).
- Add `PatchingWorkflowGraphRunner` interface/entrypoint.
- Add Helidon workflow adapter services + typed DTOs.
- Keep existing sequence execution path active behind boundary.

## Phase 2: Graph Execution Path

- Implement patching workflow graph runner.
- Wire `PatchingWorkflowCoordinator` to runner behind feature flag.
- Persist state via existing `WorkflowStateMutationService`.

## Phase 3: Hardening

- Add monitor retry/timeout policy.
- Add richer normalized failure reason mapping.
- Add workflow audit metadata/checkpoints.

## Phase 4: Cleanup

- Deprecate/remove `WorkflowExecutionSequenceAgent` once parity is validated.

---

## 12) Validation Plan

## Unit tests

- Node success path transitions.
- Node failure path to terminal failure.
- Missing `hostPids` causes immediate failure.
- Monitor timeout results in failed workflow.
- State mutation calls happen at each step boundary.

## Integration tests

- End-to-end approved workflow reaches completion.
- End-to-end failures at each major step persist expected terminal state.
- Workflow status endpoints remain consistent with persisted store transitions.

---

## 13) Risks and Mitigations

1. **Dual orchestration complexity (transition period)**
   - Mitigation: feature-flag cutover; keep clear seam in coordinator.

2. **Output normalization drift from agent responses**
   - Mitigation: adapter-level strict parsing/validation + test fixtures.

3. **State divergence between graph runtime and persisted store**
   - Mitigation: persist updates at each node boundary and treat store as source of truth.

---

## 14) Summary

This design introduces LangGraph for workflows only, keeps existing chat architecture intact, and reuses existing workflow state persistence as durable truth.

The key implementation strategy is to place Helidon-managed adapter services between graph nodes and LangChain4j agents, enabling robust typed workflow execution with minimal disruption to current APIs and architecture.
