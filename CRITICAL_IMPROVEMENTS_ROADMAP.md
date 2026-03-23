# Critical Improvements Roadmap (Proposed)

This document captures proposed upgrades for the current codebase.  
It is intentionally separate from `ARCHITECTURE.md` (which describes current state).

---

## Review of Suggested Upgrades (Codebase-Verified)

### 1) Vector Search Scalability

- **Current state:** `LocalMongoVectorStore.similaritySearch(...)` loads all KB rows and computes cosine similarity in-process.
- **Risk:** O(n) scan will degrade as `kb_documents` grows.
- **Priority:** **P0**.
- **Recommended options (order):**
  1. MongoDB Atlas Vector Search
  2. FAISS (local)
  3. Qdrant
- **Action:** Introduce a pluggable retrieval adapter and switch backend by config.

### 2) Missing Re-ranking Layer

- **Current state:** top results from vector search are sent directly to generation.
- **Risk:** embedding similarity is not always answer relevance.
- **Priority:** **P0**.
- **Action:** retrieve top 20 -> rerank -> pass top 5 to prompt.
- **Candidates:** `bge-reranker` or LLM-based scorer.

### 3) Prompt Design Hardening

- **Status:** **DONE** (implemented via `PromptBuilderService` and wired in `LlmChatService`).
- **Current state:** structured RAG payload is now used with explicit blocks.
- **Delivered format:**
  - `[CONTEXT]`
  - `[QUESTION]`
  - `[INSTRUCTIONS]`
- **Delivered controls:** explicit fallback text for insufficient context, no outside knowledge rule, and source labels from metadata when available.

### 4) Unknown Query Pipeline (Partially Complete)

- **Current state:** unknown queries are logged; grouped processing + draft generation exists; review/import flow exists.
- **Gap:** grouping is normalization-based, not semantic clustering.
- **Priority:** **P1**.
- **Action:** add semantic clustering and confidence thresholding before auto-import.

### 5) Conversation Memory Limitation

- **Current state:** `ConversationHistoryService` keeps history in JVM memory.
- **Risk:** lost on restart, not shared across replicas.
- **Priority:** **P0**.
- **Action:** move to Redis (preferred) or Mongo-backed session store.

### 6) Missing Hybrid Search

- **Status:** **DONE (Phase 2 added)**.
- **Delivered in code:**
  - Added `HybridRetrievalService` as a separate retrieval layer.
  - Combines vector results + keyword-ranked results.
  - Uses reciprocal-rank fusion to produce a unified ranking.
  - Wired into `LlmChatService` retrieval path.
  - Phase 2: keyword side now uses MongoDB `$text` search with text-score ordering and an ensured text index on KB fields.
- **Config added:**
  - `conf.rag.hybrid.enabled`
  - `conf.rag.hybrid.keyword-top-k`
  - `conf.rag.hybrid.vector-weight`
  - `conf.rag.hybrid.keyword-weight`
- **Note:** keyword retrieval now uses Mongo text index; external BM25/vector DB can still be added later for larger-scale workloads.

### 7) Embedding Consistency Metadata

- **Status:** **DONE** (backward-compatible rollout).
- **Delivered in code:**
  - `KnowledgeDocument` now stores `embeddingModel` and `embeddingVersion`.
  - `LocalMongoVectorStore.add(...)` stamps both fields on new chunks.
  - Retrieval checks doc metadata against active embedding identity.
  - Compatibility mode is configurable:
    - `conf.kb.embedding-compatibility.strict=false` -> warn only (allow docs)
    - `conf.kb.embedding-compatibility.strict=true` -> skip mismatched docs
- **Config added:**
  - `conf.kb.embedding-metadata.model-tag`
  - `conf.kb.embedding-metadata.version`

### 8) Streaming Responses

- **Current state:** synchronous full-response endpoints.
- **Risk:** slower perceived UX.
- **Priority:** **P2**.
- **Action:** add SSE/WebFlux streaming endpoints for token streaming.

---

## Prioritized Backlog

### Phase 1 (Immediate / High Impact)

1. Replace O(n) vector scan with indexed vector backend.
2. Add reranking stage to retrieval pipeline.
3. Persist and enforce embedding model/version compatibility.
4. Move conversation memory to Redis.

### Phase 2 (Quality / Reliability)

5. Add hybrid search (vector + keyword).
6. Harden prompt payload structure and source citation policy.
7. Upgrade unknown-query grouping to semantic clustering with confidence gates.

### Phase 3 (UX / Architectural Maturity)

8. Add streaming responses.
9. Introduce orchestrator layer to separate retrieval, reranking, memory, prompt building, and generation.

---

## Target Architecture (Planned)

```
ChatController
   -> Orchestrator
      -> Retrieval Service (Vector DB + Keyword Search)
      -> Reranker
      -> Memory Service (Redis/Mongo)
      -> Prompt Builder
      -> ChatModel
```
