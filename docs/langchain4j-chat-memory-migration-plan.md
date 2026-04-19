# LangChain4j Chat Memory Migration Plan

## Status

Planned only. This document captures the agreed migration plan so it can be implemented later.

## Goal

Align this project's conversation memory design more closely with the Helidon + LangChain4j chat memory guidance, while preserving our current operational needs:

- keep **Redis** and **MongoDB** as persistence options
- support **eviction only for the in-memory implementation**
- preserve our existing **`TaskContext`-driven workflow state**

## Why Change

Our current memory implementation is a custom persistence layer around:

- a single summary string
- a persisted `TaskContext` snapshot

That gives us useful continuity, but it does **not** match LangChain4j's chat memory model, which is centered on storing and managing a **list of `ChatMessage` objects**.

The Helidon / LangChain4j guidance is better aligned with:

- message-based memory instead of summary-only memory
- standard `ChatMemory` and `ChatMemoryStore` abstractions
- built-in or custom eviction behavior
- cleaner integration with AI services and agent flows

## Current State Summary

### Current classes

- `ConversationMemoryStore`
- `ConversationMemoryService`
- `ConversationMemoryStoreFactory`
- `InMemoryConversationMemoryStore`
- `RedisConversationMemoryStore`
- `MongoConversationMemoryStore`
- `ChatBotEndpoint`

### Current behavior

- memory is keyed by conversation ID (or fallback identity)
- only summary + `TaskContext` are persisted
- no persisted list of `ChatMessage`s exists
- no LangChain4j-style memory API is used
- in-memory implementation has **no eviction** today

## Target State

Move to a design where conversation memory is managed through LangChain4j-style message memory:

- conversation state is represented as **`List<ChatMessage>`**
- memory is exposed through a **`ChatMemory`** abstraction
- Redis and Mongo persist message history
- in-memory memory uses a **windowed eviction policy**
- workflow state (`TaskContext`) remains available alongside chat memory

## Recommended Design

### 1. Separate two kinds of state

Keep these concerns distinct:

1. **Chat memory**
   - actual conversational message history
   - user / AI / system / tool messages
   - used for LLM continuity

2. **Workflow state**
   - `TaskContext`
   - domain, servers, hosts, pending follow-up, approvals, intent hints
   - used for orchestration and operational safety

This avoids forcing `TaskContext` into the message history model.

### 2. Use LangChain4j-style chat memory for conversation history

Adopt a `ChatMemory`-oriented model for the actual conversation.

#### In-memory provider

Use a windowed memory implementation with eviction, such as:

- `MessageWindowChatMemory`

This is the **only provider that should evict**.

Suggested config:

- `memory.max-messages: 20` (or similar)

#### Redis and Mongo providers

Use persistent message storage with **no eviction policy in the store layer**.

Two implementation options are possible:

##### Preferred option

Create project-local persistent chat memory support that:

- stores the full `List<ChatMessage>` in Redis or Mongo
- loads all messages for a conversation ID
- updates the full message list on each turn
- deletes messages when memory is cleared

This can be done either by:

- implementing a custom `ChatMemoryStore` and pairing it with a compatible `ChatMemory` strategy, or
- implementing a project-local `ChatMemory` class for persistent full-history behavior

##### Acceptable fallback option

Use a LangChain4j window-based memory with a very high limit for Redis/Mongo.

This is easier, but it is **not as clean**, because it still implies a window policy. If we want eviction only in-memory, the preferred option is better.

## Proposed Architecture

### New / updated responsibilities

#### Chat memory layer

- **new**: `ChatMemoryProvider` or `ChatMemoryFactory`
  - resolves provider from config
  - returns a `ChatMemory` instance for a conversation ID

- **new**: `RedisChatMessageStore` / `MongoChatMessageStore`
  - persist serialized `ChatMessage` lists
  - keyed by conversation ID

- **updated**: current in-memory provider
  - move from `ConcurrentHashMap` summary storage to message-window memory

#### Workflow state layer

- keep `TaskContext` persistence separate
- retain current context merge / enrichment / follow-up logic in `ChatBotEndpoint`

This gives us standards-based chat memory without losing operational state handling.

## Serialization Strategy

Redis and Mongo persistent stores should save:

- conversation ID
- serialized `List<ChatMessage>`

Recommended approach:

- use LangChain4j chat message serializers/deserializers where available
- otherwise store a normalized JSON representation per message type

Required message types to support:

- `UserMessage`
- `AiMessage`
- `SystemMessage`
- tool-related messages if/when used

## Implementation Phases

### Phase 1 - Introduce message-based memory abstraction

1. Add a chat memory factory/provider layer
2. Add a new persistent message store abstraction if needed
3. Keep existing `TaskContext` persistence untouched initially

### Phase 2 - Implement providers

#### In-memory

- replace current in-memory summary map approach with windowed message memory
- add config-driven `maxMessages`
- confirm eviction works

#### Redis

- store serialized message history keyed by conversation ID
- preserve current TTL behavior if desired
- no eviction beyond TTL-based retention

#### Mongo

- store serialized message history in one document per conversation
- no eviction in the memory layer

### Phase 3 - Integrate with request flow

Update `ChatBotEndpoint` so that per request it:

1. resolves the conversation ID / memory key
2. gets the appropriate `ChatMemory` instance
3. appends the incoming `UserMessage`
4. invokes the agent using message history + `TaskContext`
5. appends the resulting `AiMessage`
6. persists updated `TaskContext` separately

### Phase 4 - Reduce dependence on summary-only continuity

Transition summary handling from being the **primary memory mechanism** to being a **secondary optimization**.

Recommended direction:

- keep summarization for prompt compaction when useful
- do not treat summary storage as the authoritative conversation memory model

## Suggested Code Changes

### New classes likely needed

- `ChatMemoryFactory` or `ConversationChatMemoryService`
- `RedisChatMessageStore`
- `MongoChatMessageStore`
- optionally `PersistentFullHistoryChatMemory` if built-in LangChain4j memory types do not fit the no-eviction Redis/Mongo requirement cleanly

### Existing classes to refactor

- `ConversationMemoryService`
  - evolve from summary/context store access to chat memory access

- `ConversationMemoryStoreFactory`
  - either replace or narrow to `TaskContext` persistence only

- `InMemoryConversationMemoryStore`
  - retire or repurpose into in-memory workflow state only

- `RedisConversationMemoryStore`
  - retire or split into:
    - chat message persistence
    - task context persistence

- `MongoConversationMemoryStore`
  - same split as above

- `ChatBotEndpoint`
  - integrate `ChatMemory`
  - keep `TaskContext` merge/enrichment logic

## Configuration Changes

Add / revise config values in `application.yaml`.

Example direction:

```yaml
memory:
  provider: in-memory   # in-memory | redis | mongo
  max-messages: 20      # applies only to in-memory eviction
  redis:
    ttl-seconds: 86400
```

Notes:

- `max-messages` should apply only to in-memory provider
- Redis TTL is retention, not chat-memory eviction
- Mongo can remain full-history unless a separate retention policy is added later

## Testing Plan

### Unit tests

- in-memory chat memory evicts oldest messages after limit
- Redis provider round-trips serialized messages correctly
- Mongo provider round-trips serialized messages correctly
- system / AI / user message ordering is preserved

### Integration tests

- multi-turn conversation retains memory across requests
- provider switch works across in-memory / Redis / Mongo
- `TaskContext` still merges correctly across turns
- pending follow-up behavior still works after migration

### Regression checks

- domain reuse still works
- follow-up detection still works
- no accidental loss of approval / risk state in `TaskContext`

## Risks / Watchouts

1. **Mixing memory and workflow state**
   - avoid storing operational state only inside chat messages

2. **Serialization compatibility**
   - ensure message JSON format is stable and reversible

3. **Tool message handling**
   - if tool calling expands later, store logic must support those message types

4. **Prompt growth**
   - Redis/Mongo full-history memory may require summarization or compaction later

5. **Migration complexity**
   - existing summary-based continuity should not be removed in one step without fallback testing

## Recommended Migration Strategy

Implement in two layers instead of big-bang replacement:

### Step A

Introduce message-based chat memory **alongside** existing summary / `TaskContext` logic.

### Step B

Switch `ChatBotEndpoint` to treat message memory as primary conversational continuity.

### Step C

Keep summary memory only as an optimization or compatibility fallback.

This lowers risk and makes rollback easier.

## Acceptance Criteria

The migration is complete when:

- chat continuity is backed by `ChatMessage` history, not only summary text
- in-memory provider evicts by configured window size
- Redis provider persists chat memory without memory-layer eviction
- Mongo provider persists chat memory without memory-layer eviction
- `TaskContext` orchestration behavior remains intact
- endpoint flow successfully loads, updates, and persists chat memory per conversation

## Deferred / Optional Enhancements

- token-based eviction for in-memory provider
- background summarization for long persistent histories
- conversation clear/reset endpoint
- migration utility for existing summary-based records
- observability metrics for memory size and retention

## Final Recommendation

Proceed with a **hybrid design**:

- **LangChain4j-style `ChatMemory` for message history**
- **separate persisted `TaskContext` for workflow state**

That is the cleanest way to conform to the Helidon / LangChain4j guidance without losing the operational behavior that this application already depends on.