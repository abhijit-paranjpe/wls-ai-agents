# Workflow Approval + Sequence Execution Refactor Plan

## Purpose

Define a concrete implementation plan to:

1. Separate approval handling from execution handling.
2. Generate `workflowId` only after approval is granted.
3. Execute approved workflows via a SequenceAgent-based step pipeline.
4. Introduce a dedicated MonitoringAgent for async operation tracking and workflow status queries.
5. Ensure any failure terminates workflow execution and records the exact failure reason in workflow state.

---

## Background / Problem Statement

Current execution is orchestrated by `WorkflowSupervisorAgent` calling specialist agents directly (`DomainRuntimeAgent`, `PatchingAgent`). This provides deterministic Java control but loses sequence-level agent continuity between independent calls.

Observed consequence:

- Async tracking identifiers (`hostPids`) can be missing in follow-up calls.
- Recovery attempts depend on re-reading prior tool evidence that may not be present in subsequent turns.

Desired direction:

- Keep deterministic approval and persistence logic.
- Move approved execution into a sequence-style execution flow.
- Make monitoring a dedicated concern.

---

## Target Architecture

## 1) Approval Path (No Workflow ID Yet)

Responsibilities:

- Build and present patching proposal.
- Collect human approval.
- Validate domain/constraints.
- Do **not** create executable workflow record before approval.

Primary components:

- `PatchingWorkflowAgent`
- `HumanApprovalAgent`
- `PatchingWorkflowCoordinator` (approval path)

Output of this phase:

- Approval decision + approved execution intent.

## 2) Execution Path (Workflow ID Created Post-Approval)

On approval accepted:

1. Generate `workflowId`.
2. Create persisted `WorkflowRecord` in executable state.
3. Trigger `WorkflowExecutionSequenceAgent`.

Execution sequence (approved plan):

1. Initiate stop servers (`DomainRuntimeAgent`)
2. Monitor stop completion (`MonitoringAgent`)
3. Apply latest patches (`PatchingAgent`)
4. Monitor patch completion (`MonitoringAgent`)
5. Initiate start servers (`DomainRuntimeAgent`)
6. Monitor start completion (`MonitoringAgent`)
7. Verify domain patch level (`PatchingAgent`)

## 3) Monitoring Layer

Introduce a new `MonitoringAgent` for:

- Polling async host/pid operation status.
- Returning strict normalized JSON (`running|completed|failed`).
- Supporting workflow-related status queries where appropriate.

Note: persisted workflow state remains source of truth for workflow summary/status.

---

## Workflow Lifecycle Rules

1. **Workflow ID generation**
   - Only after approval is granted.

2. **Terminal failure semantics**
   - Any step failure immediately terminates workflow.
   - Persist `FAILED` state, failed step name, normalized failure reason, and relevant raw response excerpt.

3. **Tracking IDs**
   - Initiation steps (stop/start/apply) must produce trackable identifiers for async execution.
   - If missing, fail step immediately (no synthetic/fabricated identifiers).

4. **Observability**
   - Log step start/end/failure with `workflowId`, `stepName`, attempt counts, and normalized status.

---

## Proposed Code Changes

## New Components

1. `src/main/java/com/example/wls/agentic/ai/MonitoringAgent.java`
   - MCP-enabled tracking specialist.
   - Strict JSON output contract for monitoring.

2. `src/main/java/com/example/wls/agentic/ai/WorkflowExecutionSequenceAgent.java`
   - Sequence execution driver for approved workflows.
   - Encapsulates the 7-step execution order.

## Refactors

1. `WorkflowSupervisorAgent`
   - Convert to thin execution adapter/service **or** deprecate in favor of `WorkflowExecutionSequenceAgent`.
   - Remove late “identifier recovery from prior turn” behavior.

2. `PatchingWorkflowCoordinator`
   - Split responsibilities:
     - Approval/proposal state handling.
     - Post-approval workflow creation and execution trigger.
   - Move workflow ID generation to post-approval boundary.

3. `PatchingWorkflowEndpoint` / chat routing path
   - Ensure user receives workflow ID only after approval success.
   - Keep pre-approval responses as proposal/approval artifacts.

## Data Model / State Adjustments

Update (or confirm) workflow record fields for robust failure/status behavior:

- `failureReason`
- failed step name (if separate field exists; otherwise embed in reason/details)
- step-level raw response summary
- optional execution metadata (attempt count, last monitor timestamp)

---

## Prompt & Contract Strategy

## DomainRuntimeAgent contract

- Initiation actions only (start/stop).
- Return strict JSON with async metadata and `hostPids`.

## PatchingAgent contract

- Apply patches and verify patch status.
- Return strict JSON with `hostPids` for async patch apply.

## MonitoringAgent contract

- Accept host/pid + operation context.
- Return strict JSON:
  - `status`: `running|completed|failed`
  - `operation`: `track-async-job`
  - `domain`
  - `async=false`
  - `message`
  - optional host/pid echo

---

## Failure Handling Design

Failure categories:

1. Invalid JSON / malformed response.
2. Missing required fields.
3. Unsupported status values.
4. Missing async tracking identifiers.
5. Monitoring timeout.
6. Explicit tool/operation failure.

Unified behavior for all categories:

- Mark current step `FAILED`.
- Mark workflow `FAILED`.
- Persist normalized reason.
- Stop remaining sequence steps.

---

## Implementation Phases

## Phase 1: Approval/Execution Boundary

- Move workflow ID generation to post-approval path.
- Keep existing execution path initially; no sequence adoption yet.

## Phase 2: MonitoringAgent Introduction

- Add `MonitoringAgent` and monitoring JSON contract.
- Route async polling calls through it.

## Phase 3: SequenceAgent Execution Adoption

- Add `WorkflowExecutionSequenceAgent` with the 7-step sequence.
- Wire approved workflows to this execution engine.

## Phase 4: Supervisor Simplification

- Decommission or slim `WorkflowSupervisorAgent`.
- Remove cross-turn identifier recovery logic.

## Phase 5: Status Query Improvements

- Add/route workflow status queries to state-backed summary path with optional MonitoringAgent assistance.

---

## Validation & Test Plan

## Unit Tests

1. Approval accepted creates workflow ID; approval pending/denied does not.
2. Sequence executes all 7 steps on happy path.
3. Any step failure terminates sequence and sets workflow `FAILED` with reason.
4. Monitoring timeouts fail workflow with explicit timeout reason.
5. Missing `hostPids` on async initiation fails immediately.

## Integration Tests

1. End-to-end: proposal -> approval -> workflow creation -> execution -> completion.
2. End-to-end failure at each major step (stop, patch apply, start, verify).
3. Workflow status query returns consistent state transitions.

## Logging Verification

Confirm logs include:

- `workflowId`
- step boundaries (`START`, `DONE`, `FAILED`)
- monitoring attempt counts
- terminal failure reason

---

## Migration / Rollout Plan

1. Introduce new components behind a feature flag (recommended).
2. Dual-run in lower environment for comparison:
   - old supervisor path
   - new sequence path
3. Validate parity on successful workflows.
4. Cut over fully after failure-path validation.

Rollback:

- Disable sequence execution flag and revert to existing orchestrator path.

---

## Open Decisions

1. Should `WorkflowSupervisorAgent` be retained as adapter or removed entirely?
2. Should monitoring for workflow status queries be fully deterministic from store, with agent only for formatting?
3. Do we support resume from mid-sequence step in v1, or only restart execution?

---

## Definition of Done

- Approval and execution concerns are separated.
- `workflowId` is generated only post-approval.
- Approved workflows run through SequenceAgent 7-step pipeline.
- MonitoringAgent handles async tracking.
- Any failure terminates workflow and persists failure reason.
- Workflow status queries are reliable and traceable through persisted state.
- Tests and logs validate happy path and all major failure paths.
