# Chat Memory Design Notes

These notes summarize a discussion on whether the LangChain4j chat memory tutorial (https://github.com/langchain4j/langchain4j/blob/main/docs/docs/tutorials/chat-memory.md) is used in this project, the current design, and key concepts like `ChatMemoryStore` for persistent backends and `TaskContext` as workflow state.

## Is LangChain4j Chat Memory Used?

**Yes, partially in a hybrid way.**

The tutorial describes:
- Representing conversation state as a `List<ChatMessage>`.
- Managing it via `ChatMemory` and optional `ChatMemoryStore`.
- Applying eviction/window strategies.

In this repo:
- `ChatMemory` and `ChatMemoryStore` abstractions are used for message history.
- `MessageWindowChatMemory` for in-memory (with eviction).
- Custom `PersistentConversationChatMemory` for Redis/Mongo (full history, no eviction).
- Messages are serialized/deserialized via `ChatMessageJsonCodec`.
- However, the design is hybrid: LangChain4j handles chat transcript, but legacy mechanisms persist summary and `TaskContext` separately.
- The endpoint (`ChatBotEndpoint`) manually assembles prompts (transcript + summary + `TaskContext`), rather than fully delegating to LangChain4j AI services.

## Current Design Overview

The repo uses **two parallel memory lanes**:

1. **LangChain4j Chat Memory** (conversational transcript):
   - Stores `UserMessage`, `AiMessage`, `SystemMessage` (and future tool messages).
   - Keyed by conversation ID.
   - Provider-specific: in-memory (windowed), Redis/Mongo (full history).

2. **Legacy/Custom Continuity** (operational state):
   - Summary text for prompt compaction.
   - `TaskContext` for workflow/orchestration state.

### Provider Selection

From `ConversationMemoryService.chatMemory(conversationId)`:

```
if provider = "in-memory":
  -> MessageWindowChatMemory + InMemoryConversationChatMemoryStore
     (eviction via maxMessages)

if provider = "redis" or "mongo":
  -> PersistentConversationChatMemory + Redis/Mongo ChatMemoryStore
     (full history, no eviction)
```

### Request Flow in `/chat`

```
1. Parse incoming: message + summary + optional TaskContext
2. Resolve memory key (conversation ID)
3. Load legacy state: loadPersistedTaskContext() + loadPersistedSummary()
4. Load chat messages: loadConversationMessages() -> renderTranscript()
5. Merge/enrich TaskContext (infer domain/server/host from question)
6. Call agent.chat(question, summary, transcript, TaskContext prompt, TaskContext)
7. Append turn: appendConversationTurn() (UserMessage + AiMessage to ChatMemory)
8. Persist: savePersistedSummary() + savePersistedTaskContext()
```

Chat memory provides transcript continuity; `TaskContext` drives workflow logic.

### What Gets Stored

#### Message History (ChatMemory / ChatMemoryStore)
- In-memory: `ConcurrentHashMap<Object, List<ChatMessage>>`
- Redis: Key `wls-agent:chat-messages:<id>`, JSON-serialized list, TTL
- Mongo: Document per conversation, field `chatMessages` with JSON list

#### Workflow State (ConversationMemoryStore)
- Summary: Compact interaction summary
- TaskContext: Structured fields like `targetDomain`, `targetServers`, `awaitingFollowUp`, `pendingIntent`

## ChatMemoryStore for Redis and MongoDB

`ChatMemoryStore` is a backend-agnostic interface:

```
getMessages(memoryId)    // Load List<ChatMessage>
updateMessages(memoryId, messages)  // Save list
deleteMessages(memoryId) // Delete
```

Redis/Mongo fit naturally:
- **Redis**: Key-value store; serialize list to JSON, use TTL for retention.
- **Mongo**: Document store; one doc per conversation ID, `chatMessages` field with JSON.

`ChatMemoryStore` handles persistence; `ChatMemory` (e.g., `PersistentConversationChatMemory`) adds behavior like SystemMessage replacement and full-history append.

This separation allows Redis/Mongo as valid backends without built-in eviction (unlike in-memory windowing).

## TaskContext as Workflow State

`TaskContext` is **operational/workflow state**, not just chat history. It resolves ambiguities and guides multi-step actions.

### Example: Restart Request with Follow-Ups

User conversation:
```
User: Restart the managed server
Assistant: Which domain should I use?
User: prod_domain
Assistant: Do you want a graceful restart?
User: yes
```

TaskContext evolution:

After first message:
```json
{
  "intent": "restart_server",
  "targetDomain": null,
  "awaitingFollowUp": true,
  "pendingIntent": "restart_server",
  "lastAssistantQuestion": "Which domain should I use?"
}
```

After "prod_domain":
```json
{
  "intent": "restart_server",
  "targetDomain": "prod_domain",
  "awaitingFollowUp": true,
  "pendingIntent": "restart_server",
  "lastAssistantQuestion": "Do you want a graceful restart?"
}
```

After "yes":
```json
{
  "intent": "restart_server",
  "targetDomain": "prod_domain",
  "awaitingFollowUp": false,
  "constraints": "graceful restart approved"
}
```

**Why workflow state?**
- Chat history alone makes "yes" ambiguous (domain? restart mode? approval?).
- TaskContext provides: resolved targets, pending intent, follow-up flags, last question context.
- Enables safe execution: e.g., infer "graceful restart on prod_domain server".

Another example: Async PID tracking remembers `hostPids` for follow-ups like "Check again".

### Distinction
- **Chat Memory**: "What was said?" (transcript for LLM continuity)
- **TaskContext**: "What state is the operation in?" (targets, approvals, pending steps for orchestration)

## Bottom-Line Assessment

- **Aligned with Tutorial**: Message-based `ChatMemory`, `ChatMemoryStore`, window eviction.
- **Custom Extensions**: Hybrid with separate `TaskContext`/summary; full-history for persistent providers; endpoint-driven prompt assembly.
- **Rationale**: Balances conversational history with WebLogic operational safety (targets, approvals, follow-ups).

This design supports multi-turn operations without losing context, while keeping chat transcript standards-compliant.

Last updated: 2026-04-22