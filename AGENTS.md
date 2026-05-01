# AGENTS.md

## Purpose

This file is a **lean project memory bank** for AI assistants working in this repository.
Keep it short, stable, and focused on facts that reduce repeated rediscovery.

## Project Snapshot

- **Project**: WebLogic Helidon Agentic POC
- **Artifact**: `com.example.wls:wls-agents:1.0.0-SNAPSHOT`
- **Language**: Java 21
- **Build tool**: Maven
- **Main class**: `com.oracle.wls.agentic.Server`

## Primary Stack

- Helidon SE
- LangChain4j
- MCP integration for operational tools
- Optional conversation memory backends:
  - in-memory
  - Redis
  - MongoDB

## High-Level Architecture

The assistant follows a multi-agent pipeline:

1. `RequestClassifierAgent`
2. `RequestRouterAgent`
3. One specialist agent
4. `SummarizerAgent`

Main orchestrator: `WebLogicAgent`

Primary specialist agents:

- `DomainRuntimeAgent`
- `PatchingAgent`
- `DiagnosticAgent`
- `AppManagementAgent`

Shared model key: `wls-shared-model`

Configured MCP client: `wls-tools-mcp-server`

## Key Entry Points

- REST endpoint: `src/main/java/com/example/wls/agentic/rest/ChatBotEndpoint.java`
- Orchestrator: `src/main/java/com/example/wls/agentic/ai/WebLogicAgent.java`
- Request/response DTOs:
  - `src/main/java/com/example/wls/agentic/dto/AgentResponse.java`
  - `src/main/java/com/example/wls/agentic/dto/TaskContext.java`
  - `src/main/java/com/example/wls/agentic/dto/TaskContexts.java`

## Important Packages

- `com.oracle.wls.agentic.ai`
  - orchestration, routing, classifier, summarizer, specialist agents
- `com.oracle.wls.agentic.rest`
  - HTTP entrypoint for `/chat`
- `com.oracle.wls.agentic.memory`
  - conversation memory abstractions and implementations
- `com.oracle.wls.agentic.dto`
  - transport objects and task context state

## Request and Memory Model

- `POST /chat` accepts:
  - `message`
  - `summary`
  - optional `taskContext`
- `TaskContext` carries continuity such as:
  - target domain
  - target servers / hosts
  - constraints
  - risk / approval hints
  - `memorySummary`
- `SummarizerAgent` keeps continuity compact to reduce prompt bloat.
- `ChatBotEndpoint` performs defensive parsing, context enrichment, and truncation guards.

## Common Commands

- Build package: `mvn clean package`
- Run tests: `mvn test`
- Fast compile: `mvn -q -DskipTests compile`

## Agent Working Preferences for This Repo

- Prefer **targeted changes** over broad repo-wide edits.
- Prefer **minimal diffs** unless a refactor is explicitly requested.
- Preserve the current **multi-agent architecture** unless asked to redesign it.
- For speed-sensitive requests, do a **focused file inspection first** instead of a full-project scan.
- Use existing docs before inferring architecture:
  - `README.md`
  - `docs/wls-agent-design.md`

## Fast Request Template

Use this template for faster turnaround on future requests:

```text
Task: <one specific change>
Files: <exact file(s) or package>
Goal: <expected outcome>
Constraints: <minimal diff / no refactor / keep API compatible>
Validation: <none / compile only / mvn test>
Response style: <code only / short summary / detailed>
```

Example:

```text
Task: Reduce context growth in ChatBotEndpoint
Files: src/main/java/com/example/wls/agentic/rest/ChatBotEndpoint.java
Goal: Truncate taskContext fields more aggressively without changing API
Constraints: Minimal diff, no refactor
Validation: Compile only
Response style: Short summary
```

## Maintenance Rule

If this file grows beyond ~1-2 pages of stable facts, trim it.
It should remain a **context accelerator**, not a long-form design document.