# Role-Based KB and Prompt Changes (Planned)

Date: 2026-03-31

## Goal

Extend the existing customer-only chat flow to support role-based behavior (for example `customer` and `officer`) using role-specific knowledge documents and role-specific prompt instructions.

## Planned Updates

### 1) Knowledge Base Seeding

- Update `src/main/java/ai_chat/KnowledgeBaseSeedRunner.java` to also load `src/main/resources/kb/officer_kb.json`.
- Support role metadata during seed import, so each document can be filtered by role during retrieval.
- Keep existing customer seeding fully compatible.

### 2) Role Model Strategy (No KnowledgeDocument schema change)

- Do not extend `KnowledgeDocument` for a new role field.
- Keep officer data maintained as a separate KB source file: `src/main/resources/kb/officer_kb.json`.
- Keep/align the officer JSON structure with `OFFICER_KB_MODEL.md`.
- Implement role-aware retrieval by filtering on existing document metadata and source/category conventions, without changing Mongo document schema.

### 3) Chat APIs

- Extend request model(s) to accept an optional role field (default to `customer` for backward compatibility).
- Update chat endpoints that use RAG so they pass role into service methods.
- Keep existing payloads valid for current clients.

### 4) Prompt Builder + System Prompt

- Update `PromptBuilderService` to build role-aware payloads/instructions.
- Update RAG system prompt in chat service so style and scope can adapt by role while still enforcing grounding.
- Preserve strict "insufficient information" fallback behavior.

### 5) Service Layer Changes

- Update `ChatService` interface and `LlmChatService` implementation signatures where needed to accept role.
- Ensure role is applied consistently for:
  - retrieval query flow
  - prompt construction
  - response generation with history

## Compatibility and Safety

- No breaking changes for existing customer clients:
  - role remains optional
  - default role is `customer`
- Existing customer KB behavior remains unchanged unless role is explicitly provided.

## Verification Plan

- Start app and confirm seeding includes both customer and officer KB entries.
- Verify `/chat/rag` and `/chat/rag/conversation` return:
  - customer-focused responses with default role
  - officer-focused responses when role is `officer`
- Validate fallback response still triggers when role-filtered context is insufficient.

