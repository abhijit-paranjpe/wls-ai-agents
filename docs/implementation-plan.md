# Patching Workflow Phase 1 Implementation Plan

## 1. Objective

Implement Phase 1 of the patching workflow from `docs/Patching-workflow-v2.md` with:

- Human-in-the-loop approval using `WorkflowApprovalSemaphore`
- Authoritative workflow state in a pluggable store (in-memory first)
- Domain concurrency control with a pluggable lock manager (in-memory first)
- Chat and REST API support for proposal/approval/status/listing
- Async execution orchestration with approval and execution timeouts

---

## 2. Phase 1 Scope and Decisions

- Support both natural-language chat and REST APIs, backed by shared workflow services.
- Keep `WorkflowApprovalSemaphore` as a core HITL demonstration component.
- External approval decisions in Phase 1: `APPROVE`, `REJECT`, `CANCEL`.
- Reject new same-domain patch requests when a non-terminal workflow already exists; include existing `workflowId` in the response.
- Implement approval timeout and execution timeout in Phase 1.
- Defer step-specific timeout implementation.
- Extend `TaskContext` minimally with `activeWorkflowId`.
- Persist approval metadata as decision + timestamp + channel (`chat` or `api`) in Phase 1 (no approver identity).

---

## 3. Implementation Phases

## Phase 1 — Core Workflow Model and Contracts

### Goal
Create the shared model types and interfaces used by all workflow components.

### Deliverables
- Workflow model types (enums/records/classes):
  - `WorkflowStatus`
  - `ApprovalDecision`
  - `WorkflowChannel`
  - `WorkflowSummary`
  - `WorkflowRecord`
  - `WorkflowStepRecord`
- API DTOs:
  - `ApprovalRequest`
  - `ApprovalResponse`
  - status/list response models using shared summary shape
- Interfaces:
  - `WorkflowStateStore`
  - `DomainLockManager`

### Suggested file locations
- `src/main/java/com/example/wls/agentic/workflow/`
- `src/main/java/com/example/wls/agentic/dto/` (if DTOs are kept with existing DTO package)

### Completion criteria
- All model types compile.
- Shared summary response shape includes:
  - `workflowId`, `domain`, `currentState`, `createdAt`, `updatedAt`, optional correlation info, `requestSummary`.

### Manual tests
#### Test 1.1: Compile validation
**Preconditions:** none  
**Steps:**
1. Run `mvn -q -DskipTests compile`
**Expected result:**
- Build succeeds.

#### Test 1.2: DTO shape review
**Preconditions:** model/DTO classes created  
**Steps:**
1. Inspect generated JSON serialization behavior (unit test or endpoint stub).
2. Confirm required fields are present in summary response model.
**Expected result:**
- Response shape matches design contract.

---

## Phase 2 — In-Memory State, Locking, and Approval Semaphore

### Goal
Add thread-safe in-memory implementations for state/locking and implement approval synchronization.

### Deliverables
- `InMemoryWorkflowStateStore`
- `InMemoryDomainLockManager`
- `WorkflowApprovalSemaphore`

### Completion criteria
- Store supports create/update/query/list operations.
- Lock manager supports owner-aware acquire/release.
- Semaphore supports `awaitDecision`, `submitDecision`, and `cancel`.

### Manual tests
#### Test 2.1: State store lifecycle
**Preconditions:** in-memory store wired in a test or temporary harness  
**Steps:**
1. Create a workflow in `DRAFT`.
2. Transition to `PROPOSED`, then `AWAITING_APPROVAL`.
3. Query by workflow ID and domain.
**Expected result:**
- State transitions are persisted and queryable.

#### Test 2.2: Listing filters
**Preconditions:** at least 3 workflows in different states  
**Steps:**
1. List all workflows.
2. List pending approval workflows.
3. List in-execution workflows.
**Expected result:**
- Lists return correct subsets by state.

#### Test 2.3: Lock ownership semantics
**Preconditions:** lock manager instance ready  
**Steps:**
1. Acquire lock for `domainA` with `workflow-1`.
2. Attempt acquire for same domain with `workflow-2`.
3. Attempt release with wrong owner.
4. Release with correct owner.
**Expected result:**
- Second acquire fails while held.
- Wrong-owner release does not unlock.
- Correct-owner release unlocks domain.

#### Test 2.4: Semaphore behavior
**Preconditions:** semaphore instance ready  
**Steps:**
1. Start a thread waiting on `awaitDecision(workflowId)`.
2. Submit `APPROVE` from another thread.
3. Repeat with `cancel(workflowId)`.
**Expected result:**
- Awaiting thread resumes with decision on submit.
- Awaiting thread is cancelled on cancel.

---

## Phase 3 — Coordinator and Query Services

### Goal
Implement deterministic orchestration/state transitions and query logic.

### Deliverables
- `PatchingWorkflowCoordinator`
- optional separate query service (or coordinator read APIs)
- same-domain non-terminal request rejection logic

### Completion criteria
- Proposal flow persists `DRAFT -> PROPOSED -> AWAITING_APPROVAL`.
- Conflicting same-domain request is rejected with existing `workflowId`.
- Query methods for by-ID, by-domain latest/active, and list views work.

### Manual tests
#### Test 3.1: Proposal creation and state transitions
**Preconditions:** coordinator wired with in-memory implementations  
**Steps:**
1. Submit a patch proposal request for domain `payments-prod`.
2. Query workflow by ID.
**Expected result:**
- Workflow exists and is in `AWAITING_APPROVAL`.

#### Test 3.2: Same-domain conflict rejection
**Preconditions:** one `AWAITING_APPROVAL` workflow for `payments-prod`  
**Steps:**
1. Submit another patch request for `payments-prod`.
**Expected result:**
- Request rejected.
- Response includes existing `workflowId`.

---

## Phase 4 — REST Workflow APIs

### Goal
Expose deterministic APIs for approval, status, and listing.

### Deliverables
- `POST /patching/workflows/{workflowId}/approval`
- `GET /patching/workflows/{workflowId}`
- `GET /patching/workflows/by-domain/{domain}/latest`
- `GET /patching/workflows/by-domain/{domain}/active`
- `GET /patching/workflows`
- `GET /patching/workflows/pending-approval`
- `GET /patching/workflows/in-execution`

### Completion criteria
- APIs return contract-compliant JSON.
- Approval API supports only `APPROVE|REJECT|CANCEL`.

### Manual tests
#### Test 4.1: Approve workflow via API
**Preconditions:** workflow in `AWAITING_APPROVAL`  
**Steps:**
1. `POST /patching/workflows/{id}/approval` with `{ "decision": "APPROVE" }`.
**Expected result:**
- Response contains `workflowId`, `domain`, decision, current state, status guidance.

#### Test 4.2: Status and list endpoints
**Preconditions:** workflows in mixed states  
**Steps:**
1. Call all status/list endpoints.
2. Validate shared summary shape in list responses.
**Expected result:**
- Correct workflows returned with required fields.

---

## Phase 5 — HITL Workflow Agents

### Goal
Add declarative HITL workflow components and route approval outcomes.

### Deliverables
- `HumanApprovalAgent`
- `PatchingWorkflowOutcomeRouterAgent`
- `PatchingWorkflowAgent`
- `PatchingWorkflowStateKeys`

### Completion criteria
- Workflow pauses at approval stage and resumes via semaphore decision.
- Outcome routing handles `APPROVED`, `REJECTED`, `CANCELLED`.

### Manual tests
#### Test 5.1: Pause and resume with semaphore
**Preconditions:** workflow agent wired with semaphore and coordinator  
**Steps:**
1. Start proposal flow to reach approval stage.
2. Submit approval via API.
**Expected result:**
- Workflow resumes and routes correctly.

#### Test 5.2: Reject and cancel paths
**Preconditions:** workflow in `AWAITING_APPROVAL`  
**Steps:**
1. Submit `REJECT`.
2. Submit `CANCEL` on another workflow.
**Expected result:**
- Terminal states are persisted correctly.

---

## Phase 6 — Async Execution and Supervisor Integration

### Goal
Run approved workflows asynchronously through execution states with lock lifecycle management.

### Deliverables
- async executor (`ExecutorService`-based)
- `WorkflowSupervisorAgent`
- state transitions across execution phases

### Completion criteria
- Approved workflows transition through queued/execution states.
- Lock acquired before execution and released in `finally`.

### Manual tests
#### Test 6.1: Successful execution flow
**Preconditions:** executable patch flow mocked or connected  
**Steps:**
1. Approve workflow.
2. Poll status endpoint.
**Expected result:**
- Transitions observed through execution states to `COMPLETED`.

#### Test 6.2: Failure cleanup
**Preconditions:** force a controlled step failure  
**Steps:**
1. Run execution.
2. Confirm failure state and lock status.
**Expected result:**
- Terminal failure state persisted.
- Domain lock released.

---

## Phase 7 — Chat Integration

### Goal
Expose proposal/approval/status/list workflow operations through natural-language chat using shared backend services.

### Deliverables
- routing and endpoint integration updates
- chat support for approval/status/list intents
- ambiguity handling for chat approval
- `TaskContext.activeWorkflowId`

### Completion criteria
- Chat commands trigger the same workflow services as APIs.
- Ambiguous `approve` prompts user for explicit `workflowId` or domain.

### Manual tests
#### Test 7.1: End-to-end chat happy path
**Preconditions:** app running with chat endpoint  
**Steps:**
1. “Apply recommended patches to payments-prod”
2. “approve”
3. “status for workflow <id>”
**Expected result:**
- Proposal + approval + execution status works through chat.

#### Test 7.2: Chat ambiguity safeguard
**Preconditions:** multiple pending workflows  
**Steps:**
1. Send “approve” without workflow ID/domain.
**Expected result:**
- Assistant asks for explicit `workflowId` or domain.

---

## Phase 8 — Timeout Handling and Hardening

### Goal
Implement approval/execution timeout behavior and finalize operational reliability for Phase 1.

### Deliverables
- approval timeout handling (`APPROVAL_TIMED_OUT`)
- execution timeout handling (`EXECUTION_TIMED_OUT`)
- relevant config properties in `application.yaml`

### Completion criteria
- Timeout transitions are deterministic and persisted.
- Lock cleanup is guaranteed for execution timeout path.

### Manual tests
#### Test 8.1: Approval timeout
**Preconditions:** short approval timeout configured  
**Steps:**
1. Create workflow and do not approve.
2. Wait for timeout.
**Expected result:**
- Workflow transitions to `APPROVAL_TIMED_OUT`.
- Late approval is rejected.

#### Test 8.2: Execution timeout
**Preconditions:** short execution timeout configured  
**Steps:**
1. Approve workflow.
2. Allow execution to exceed timeout.
**Expected result:**
- Workflow transitions to `EXECUTION_TIMED_OUT`.
- Lock is released.

---

## 4. Final End-to-End Demo Checklist

1. Create patch proposal through chat.
2. Verify workflow appears in pending approval list API.
3. Approve workflow via API.
4. Verify workflow appears in in-execution list API.
5. Query workflow by ID and by domain (`latest`, `active`).
6. Attempt conflicting same-domain request and validate rejection + existing `workflowId`.
7. Validate reject/cancel flows.
8. Validate timeout scenarios (approval and execution).

---

## 5. Suggested Validation Commands

- Compile: `mvn -q -DskipTests compile`
- Run tests: `mvn test`

Use `curl` or API client for REST endpoint manual checks after each phase.
