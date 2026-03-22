# Short-term chat history — work list

**Status: implemented** (see `ConversationHistoryService`, `LlmChatService` conversation methods, `ChatController` `/chat/conversation` and `/chat/rag/conversation`, `conf.chat.*` in `application.yml`).

Original checklist (for traceability):

| # | Item | Notes |
|---|------|--------|
| 1 | API shape | JSON: `message` (required), `conversationId` (optional). Response: `conversationId`, `reply`. |
| 2 | Backward compatibility | Legacy `POST /chat` and `POST /chat/rag` unchanged (raw string). |
| 3 | OpenAPI | Controller `@Operation` descriptions on new endpoints. |
| 4 | Session id | `ConversationHistoryService.resolveOrCreateConversationId` — UUID when omitted; max id length 128. |
| 5 | Storage v1 | In-memory `ConcurrentHashMap` per JVM; max messages + TTL — see `conf.chat`. |
| 6 | Scale-out | Not in v1 — use Redis/Mongo session store if you run multiple instances. |
| 7–8 | Prompt order | `SystemMessage` → prior `User`/`Assistant` messages → final user payload. |
| 9 | RAG retrieval | `LlmChatService.buildRetrievalQuery` merges short follow-ups with last prior user line for embedding search. |
| 10 | Unknown queries | `unknown_queries` documents include `conversationId` when present. |
| 11 | Size limits | `conf.chat.history-max-messages` (default 20). |
| 12 | TTL | `conf.chat.history-session-ttl-hours` (default 24), lazy eviction. |
| 13–14 | Tests / regression | `LlmChatServiceRetrievalQueryTest`; legacy endpoints unchanged. |
| 15 | Docs | `ARCHITECTURE.md`, `PROJECT_OVERVIEW.md`, this file, `SSRP_GUIDE_RAG_EXTENSIONS.md` baseline. |

---

*For behavior and configuration, prefer **`ARCHITECTURE.md`** as the source of truth.*
