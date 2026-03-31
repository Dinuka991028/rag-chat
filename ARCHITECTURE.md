# rag-chat — Architecture & How It Works

This document describes the **technology stack**, **layered architecture**, and **request/data flow** of the `ai-chat` Spring Boot application: a Bahrain **Small Ship Registry Portal (SSRP)**–themed chat API that can answer with plain LLM calls or with **RAG** (retrieval-augmented generation) over a MongoDB knowledge base.

For a **lead/PM-oriented** overview (how the system works, how to swap LLMs, env vars), see **`PROJECT_OVERVIEW.md`**.

---

## Technology stack

| Layer | Technology |
|--------|------------|
| Language & runtime | **Java 17** |
| Framework | **Spring Boot 3.5.x** (`spring-boot-starter-web`) |
| Persistence | **MongoDB** via **Spring Data MongoDB** — **`KnowledgeDocument`** (`@Document`), **`KnowledgeDocumentRepository`**, plus **`MongoTemplate`** for `unknown_queries` (still BSON `Document`) |
| LLM & embeddings | **Spring AI** **`ChatModel`** + **`EmbeddingModel`** (port/adapter: same Java API for every provider) — **`conf.ai.chat-provider`** / **`conf.ai.embedding-provider`** switch implementations (**ollama** \| **openai** \| **vertexai**); **`VectorStore`** for RAG retrieval |
| HTTP client | *(none for Ollama — Spring AI client uses configured base URL)* |
| API docs | **springdoc-openapi** (`springdoc-openapi-starter-webmvc-ui` 2.1.0) — Swagger UI |
| Vector API | **`spring-ai-vector-store`** (`VectorStore`, `SearchRequest`) |
| Build | **Maven** (`pom.xml`) |
| Optional DX | **Lombok** — used on **`KnowledgeDocument`** (`@Builder`, etc.) |

**External services you must run locally (or point config at):**

- **MongoDB** — default: `localhost:27017`, database `ai` (`application.yml`).
- **Ollama** — default: `http://localhost:11434` with models from `spring.ai.ollama.*` (e.g. `llama3` for chat and embeddings).
- **OpenAI** (optional) — profile **`openai`**, **`OPENAI_API_KEY`**; see **`application-openai.yml`**.
- **Vertex AI Gemini** (optional) — profile **`vertex-gemini`**, GCP project + **`gcloud` auth**; see **`application-vertex-gemini.yml`**.
- Changing **embedding** provider usually requires **re-seeding** **`kb_documents`** (same embedding space for stored vectors and queries).
- **HTTP** — default: port **8080**, context path **`/ai-chat`** (see `server.*` in `application.yml`).
- **Profiles** — **`dev`**, **`onsite`**, **`prod`**: `application-{profile}.yml` overrides the central **`conf:`** map. Active profile is set via Maven-filtered **`spring.profiles.active`** (`@activatedProperties@` in `application.yml`) or `--spring.profiles.active`. **`prod`** disables Swagger UI by default.

---

## High-level architecture

The app follows a classic **Spring MVC** layout:

```
HTTP (JSON/text)
    → ChatController
        → SecurityGovernanceService (request validation, policy checks)
        → ChatService (interface)
            → LlmChatService
                → ChatModel (Spring AI — provider from config) + HybridRetrievalService
                → RerankingService (LLM-based candidate reranker)
                → PromptBuilderService (builds structured RAG payload blocks)
                → ConversationHistoryService (in-memory sessions for `/chat/conversation` and `/chat/rag/conversation`)
                → MongoTemplate / KnowledgeDocumentRepository → MongoDB
```

Supporting pieces:

- **`KnowledgeBaseSeedRunner`** — **`@Order(1)`**, **`@Profile("!test")`**; if the KB is empty, seeds Spring AI **`Document`** chunks and calls **`vectorStore.add(...)`**, which persists **`KnowledgeDocument`** rows **with embeddings** (via **`EmbeddingModel`** inside **`LocalMongoVectorStore`**). Seeding includes customer KB JSON/SRS sources and optional streaming import of `kb/officer_kb.json` into officer-tagged chunks (`audienceRole=officer`, `category=OfficerJobSummary`, `source=officer-kb-json`).

**`LocalMongoVectorStore`** implements **`VectorStore`** (`add` + `similaritySearch`): cosine search over stored embeddings.
RAG retrieval now runs through **`HybridRetrievalService`**, which fuses vector and keyword-ranked results (configurable).

---

## Package layout (`ai_chat`)

| Area | Responsibility |
|------|----------------|
| `AiChatApplication` | `@SpringBootApplication` entry point |
| `KnowledgeBaseSeedRunner` | KB seeding `CommandLineRunner` **`@Order(1)`**, profile **`!test`** |
| `domain.KnowledgeDocument` | Typed Mongo entity for **`kb_documents`** |
| `repository.KnowledgeDocumentRepository` | `MongoRepository` for KB CRUD |
| `controller.ChatController` | REST endpoints under `/chat` (full path includes context path, e.g. `/ai-chat/chat`) |
| `service.ChatService` | Contract: plain + RAG (including role-aware overloads); plus conversation variants returning **`ChatConversationResponse`** |
| `service.impl.LlmChatService` | **`ChatModel`** + RAG orchestration; merges short-term history into prompts/retrieval query and applies role-specific retrieval + prompt behavior |
| `service.HybridRetrievalService` | Hybrid retrieval starter: vector search + keyword ranking + reciprocal-rank fusion |
| `service.RerankingService` | LLM-based reranking layer that scores retrieval candidates and keeps top-k |
| `service.SecurityGovernanceService` | Security and governance layer that validates inbound requests before chat/RAG processing |
| `service.PromptBuilderService` | Builds structured RAG user payload with `[CONTEXT]`, `[ROLE]`, `[QUESTION]`, `[INSTRUCTIONS]` and role-specific guidance |
| `service.ConversationHistoryService` | In-memory **`conversationId`** → recent **`Message`** list (cap + TTL from **`conf.chat`**) |
| `dto.ChatConversationRequest` / `ChatConversationResponse` | JSON body/response for multi-turn endpoints (`role` supported in request) |
| `vectorstore.LocalMongoVectorStore` | **`VectorStore`** implementation (local Mongo + cosine search) |
| `config.VectorStoreConfig` | **`VectorStore`** bean |
| `config.OpenApiConfig` | OpenAPI metadata for Swagger |
| `domain.KbTrainingDraft` | Typed Mongo entity for **`kb_training_drafts`** (draft review queue) |
| `repository.KbTrainingDraftRepository` | `MongoRepository` for draft lifecycle queries |
| `training.UnknownQueryNormalizer` | Normalization + grouping utility for unknown questions |
| `service.UnknownQueryTrainingService` | Scheduled pipeline that creates drafts from `unknown_queries` |

---

## REST API

| Method & path | Body | Behavior |
|---------------|------|----------|
| `POST /ai-chat/chat` | Raw string (message) | Calls **`askAI`**: system prompt for SSRP + user message → **`ChatModel`** — **no** KB retrieval. |
| `POST /ai-chat/chat/rag` | Raw string (message), optional query param `role` (`customer` default, `officer` supported) | Calls **`askAIWithContext`**: embed query, retrieve role-scoped KB docs, then **generate** with role-aware grounded instructions. |
| `POST /ai-chat/chat/conversation` | JSON `{"message":"…","conversationId":"…"}` — `conversationId` optional | Plain chat with **short-term history**: prior turns + current message. Response JSON: **`conversationId`**, **`reply`**. |
| `POST /ai-chat/chat/rag/conversation` | JSON `{"message":"…","conversationId":"…","role":"officer|customer"}` (`role` optional, defaults to `customer`) | RAG with history: retrieval uses a **combined query** when the latest message is short (e.g. “yes”) so it aligns with the **previous user** line; generation sees history + role-scoped KB excerpts. |

All chat endpoints run through a **Security & Governance** check first. Requests that violate policy (empty payload, over-limit size, or blocked sensitive patterns) are rejected before retrieval/model execution. Responses are also post-checked for customer-id integrity to prevent ID drift (for example, `customer id 123` changing in generated output).

Swagger/OpenAPI UI is provided by springdoc (with the configured context path, e.g. **`http://localhost:8080/ai-chat/swagger-ui.html`**).

### Short-term chat history (design)

- **Sessions** are keyed by **`conversationId`** (UUID generated server-side when the client omits it on the first request; client sends it back on later requests).
- **Storage** is **in-memory in the JVM** (`ConversationHistoryService`). It is **not** shared across replicas or restarts — for horizontal scaling or durable chat, replace with Redis or Mongo-backed storage using the same interface.
- **Limits** — **`conf.chat.history-max-messages`** (default **20** messages = up to 10 user/assistant pairs) and **`conf.chat.history-session-ttl-hours`** (default **24**): idle sessions are evicted to cap memory use.
- **RAG follow-ups** — If the latest user text is shorter than **80** characters, **`LlmChatService.buildRetrievalQuery`** concatenates it with the **most recent prior user message** in history for **`similaritySearch`** only; the **customer question** line in the KB payload remains the actual latest message.

---

## How RAG works (end-to-end)

1. **Knowledge storage** — Documents live in MongoDB collection **`kb_documents`**, with fields such as `content`, `category`, `source`, **`embedding`** (list of floats), plus embedding identity metadata (**`embeddingModel`**, **`embeddingVersion`**) populated on ingest via **`VectorStore.add`**.

2. **Query embedding** — **`LocalMongoVectorStore`** uses **`EmbeddingModel.embed(query)`** for the query vector.

3. **Hybrid retrieval** — **`HybridRetrievalService`** gets vector results from **`VectorStore.similaritySearch(SearchRequest)`**, adds MongoDB **`$text`** keyword-ranked candidates (text-score order on indexed KB fields), and fuses rankings.

   - After retrieval, `LlmChatService` applies role-based document scoping (`customer` vs `officer`) using metadata/source/category conventions.
   - For `officer` role, entity-intent fast path is applied for operational queries containing ship-number tokens (for example `J-10004`): first filter by ship number, then optionally by status intent keywords (`pending`, `completed`, `canceled/cancelled`) before normal rerank fallback.

4. **Reranking** — **`RerankingService`** scores the retrieved candidate set against the user query and keeps top-k passages before final prompt composition.

5. **No match** — If nothing passes the threshold (or KB has no embeddings), the flow matches the previous **unknown query** behavior:

   - A record may be inserted into collection **`unknown_queries`** (**`question`**, **`createdAt`**, optional **`conversationId`** when the request used a conversation endpoint).
   - The user gets a fixed “not enough information” style message.
   - If enabled, **`UnknownQueryTrainingService`** periodically groups repeated unknown questions, generates a draft answer from KB excerpts, and stores it in **`kb_training_drafts`** for admin approval/import.

6. **Grounded generation** — Retrieved excerpts are formatted by **`PromptBuilderService`** into a structured **`UserMessage`** with `[CONTEXT]`, `[ROLE]`, `[QUESTION]`, and `[INSTRUCTIONS]`; **`ChatModel`** is called with a dedicated **RAG `SystemMessage`** (customer/officer variants, both KB-only). The reply comes from **`ChatResponse`**.

7. **Non-RAG chat** — `POST .../chat` skips retrieval and uses a shorter **SSRP assistant** system prompt only.

---

## How plain chat works (no RAG)

`askAI` calls **`chatModel.call(new Prompt(new SystemMessage(SSRP…), new UserMessage(message)))`** and returns the assistant text from **`ChatResponse`** (no `RestTemplate`).

**With history** — `askAIWithHistory` builds **`Prompt(SystemMessage, …history, UserMessage(latest))`** after loading a snapshot from **`ConversationHistoryService`**, then appends the user/assistant pair for the next turn.

---

## Data model (MongoDB)

- **`kb_documents`** — KB chunks for SSRP (customer and officer role content). Seeded at startup if empty through **`vectorStore.add`** (embeddings computed at seed time). New rows are tagged with **`embeddingModel`** and **`embeddingVersion`** to guard against mixed embedding spaces. Officer chunks are distinguished by metadata conventions (`audienceRole=officer`, `source=officer-kb-json`, `category=OfficerJobSummary`) without changing the `KnowledgeDocument` schema.
- **`unknown_queries`** — Optional log of user questions when RAG cannot find a confident match (best effort insert; failures are printed to stderr). Documents may include **`conversationId`** for requests from **`/chat/rag/conversation`**.
- **`kb_training_drafts`** — Admin review queue populated by **`UnknownQueryTrainingService`**. Drafts are created from normalized/grouped entries in `unknown_queries`, then imported into `kb_documents` via admin approval endpoints under **`/admin/unknown-training/drafts/**`.

---

## Configuration

`src/main/resources/application.yml` plus **`application-{profile}.yml`** (SRP-style):

- **`conf:`** — single place for app name, server port/context-path, Mongo (**`MONGODB_URI`** optional for TLS / full connection string), Ollama models/URL, springdoc toggles, logging levels, multipart limits, **`conf.chat.history-max-messages`** and **`conf.chat.history-session-ttl-hours`** for conversation endpoints.
- **Security & governance** — **`conf.security.enabled`**, **`conf.security.max-input-chars`**, and **`conf.security.blocked-patterns`** enforce mandatory inbound policy checks before any LLM or RAG action.
- **Hybrid retrieval tuning** — **`conf.rag.hybrid.enabled`**, **`conf.rag.hybrid.keyword-top-k`**, **`conf.rag.hybrid.vector-weight`**, **`conf.rag.hybrid.keyword-weight`** control vector+keyword fusion behavior.
- **Reranking tuning** — **`conf.rag.rerank.enabled`** and **`conf.rag.rerank.top-k`** control LLM-based candidate reranking.
- **KB JSON source toggles** — **`conf.kb.json-enabled`**, **`conf.kb.json-vessel-services-classpath`**, **`conf.kb.json-validations-classpath`**, **`conf.kb.json-officer-enabled`**, **`conf.kb.json-officer-classpath`** control startup seeding sources.
- **Embedding consistency controls** — **`conf.kb.embedding-metadata.model-tag`** + **`conf.kb.embedding-metadata.version`** are stamped on new KB chunks; **`conf.kb.embedding-compatibility.strict`** controls whether mismatches are warn-only (`false`) or excluded from retrieval (`true`).
- Top of **`application.yml`** maps **`spring.*`**, **`server.*`**, etc. from **`${conf.*}`** (not Keycloak/JPA/SQL Server—those are not in this project).
- Maven **`@activatedProperties@`** substitutes the default **Spring** profile at build time (`pom.xml`: profiles `dev`, `onsite`, `prod`).
- **`spring.ai.ollama.*`**, **`spring.ai.openai.*`**, **`spring.ai.vertex.ai.gemini.*`** map from **`conf.ollama.*`**, **`conf.openai.*`**, **`conf.vertex.gemini.*`**. **`LlmChatService`** injects **`ChatModel`** only — switching **`conf.ai.chat-provider`** swaps the adapter (Ollama, OpenAI, Vertex Gemini, …) with no code change.

---

## Testing

- `AiChatApplicationTests` — `@SpringBootTest` + **`@ActiveProfiles("test")`** (skips **`KnowledgeBaseSeedRunner`**; Mongo driver may still log if **`mongod`** is not running, but the smoke test does not require seeding or successful DB access).

---

## Operational notes

1. **Startup seeding** — **`KnowledgeBaseSeedRunner`** (**`@Order(1)`**, not active under **`test`**) calls **`vectorStore.add`** so each chunk is embedded once at startup.

2. **Embeddings** — **`LocalMongoVectorStore.add`** and similarity search both use **`EmbeddingModel`** (Ollama and/or OpenAI per **`conf.ai.embedding-provider`**).

3. **Model assumptions** — Default **`llama3`** for chat and embeddings when providers are Ollama; OpenAI model names live under **`conf.openai.*`**. Embedding identity metadata (`embeddingModel`, `embeddingVersion`) is stored per chunk, and retrieval can enforce compatibility via **`conf.kb.embedding-compatibility.strict`**.

---

## Summary

**rag-chat** is a **Spring Boot 3** service that exposes **chat**, **RAG chat**, and optional **conversation** endpoints (short-term in-memory history), uses **MongoDB** with **`KnowledgeDocument`** + **`VectorStore`**, and uses Spring AI **`ChatModel`** + **`EmbeddingModel`** (provider chosen in **`conf.ai.*`**) with **OpenAPI/Swagger** for API exploration.
