# rag-chat — Architecture & How It Works

This document describes the **technology stack**, **layered architecture**, and **request/data flow** of the `ai-chat` Spring Boot application: a Bahrain **Small Ship Registry Portal (SSRP)**–themed chat API that can answer with plain LLM calls or with **RAG** (retrieval-augmented generation) over a MongoDB knowledge base.

---

## Technology stack

| Layer | Technology |
|--------|------------|
| Language & runtime | **Java 17** |
| Framework | **Spring Boot 3.5.x** (`spring-boot-starter-web`) |
| Persistence | **MongoDB** via **Spring Data MongoDB** — **`KnowledgeDocument`** (`@Document`), **`KnowledgeDocumentRepository`**, plus **`MongoTemplate`** for `unknown_queries` (still BSON `Document`) |
| LLM & embeddings | **Ollama** (HTTP), model **`llama3`** — `/api/generate` for text, `/api/embeddings` for vectors |
| HTTP client | **Spring `RestTemplate`** (calls Ollama) |
| API docs | **springdoc-openapi** (`springdoc-openapi-starter-webmvc-ui` 2.1.0) — Swagger UI |
| Build | **Maven** (`pom.xml`) |
| Optional DX | **Lombok** — used on **`KnowledgeDocument`** (`@Builder`, etc.) |

**External services you must run locally (or point config at):**

- **MongoDB** — default: `localhost:27017`, database `ai` (`application.yml`).
- **Ollama** — default: `http://localhost:11434` with models from `spring.ai.ollama.*` (e.g. `llama3` for chat and embeddings).
- **HTTP** — default: port **8080**, context path **`/ai-chat`** (see `server.*` in `application.yml`).
- **Profiles** — **`dev`** (default), **`onsite`**, **`prod`**: see `application-dev.yml`, `application-onsite.yml`, `application-prod.yml`. `prod` disables Swagger UI by default.

---

## High-level architecture

The app follows a classic **Spring MVC** layout:

```
HTTP (JSON/text)
    → ChatController
        → AIService (interface)
            → OllamaServiceImpl
                → RestTemplate → Ollama
                → KnowledgeDocumentRepository / MongoTemplate → MongoDB
```

Supporting pieces:

- **`AiChatApplication`** — Spring Boot entry point; on startup **`@Order(1)`** it **seeds** `KnowledgeDocument` rows if the KB repository is empty (no embeddings yet).
- **`EmbeddingUpdater`** — **`@Order(2)`**; loads all **`KnowledgeDocument`** rows, computes **embeddings** via Ollama, **`save`**s embeddings back to MongoDB.

There is also a **`VectorSearchService`** class that implements cosine similarity for ranked retrieval; **it is not wired into `OllamaServiceImpl` today** — RAG scoring is implemented inline in `OllamaServiceImpl`. `VectorSearchService` is available for reuse or future refactoring.

---

## Package layout (`ai_chat`)

| Area | Responsibility |
|------|----------------|
| `AiChatApplication` | `@SpringBootApplication`, KB seeding `CommandLineRunner` **`@Order(1)`** |
| `domain.KnowledgeDocument` | Typed Mongo entity for **`kb_documents`** |
| `repository.KnowledgeDocumentRepository` | `MongoRepository` for KB CRUD |
| `EmbeddingUpdater` | `CommandLineRunner` **`@Order(2)`** — fills `embedding` on KB docs |
| `controller.ChatController` | REST endpoints under `/chat` (full path includes context path, e.g. `/ai-chat/chat`) |
| `service.AIService` | Contract: `askAI`, `askAIWithContext` |
| `service.impl.OllamaServiceImpl` | Ollama integration + RAG pipeline |
| `service.VectorSearchService` | Cosine similarity helpers (not used by controller path today) |
| `config.OpenApiConfig` | OpenAPI metadata for Swagger |

---

## REST API

| Method & path | Body | Behavior |
|---------------|------|----------|
| `POST /ai-chat/chat` | Raw string (message) | Calls **`askAI`**: system prompt for SSRP + user message → Ollama **generate** — **no** KB retrieval. |
| `POST /ai-chat/chat/rag` | Raw string (message) | Calls **`askAIWithContext`**: embed query, retrieve similar KB docs, then **generate** with KB-only instructions. |

Swagger/OpenAPI UI is provided by springdoc (with the configured context path, e.g. **`http://localhost:8080/ai-chat/swagger-ui.html`**).

---

## How RAG works (end-to-end)

1. **Knowledge storage** — Documents live in MongoDB collection **`kb_documents`**, with fields such as `content`, `category`, `source`, and (after `EmbeddingUpdater` runs) **`embedding`** (list of floats).

2. **Query embedding** — For `POST .../chat/rag`, `OllamaServiceImpl` calls Ollama **`/api/embeddings`** with the user’s prompt and builds a **query vector**.

3. **Similarity search** — For each document that has an `embedding`, the service computes **cosine similarity** between the query vector and the document vector (dimensions must match or the pair is skipped).

4. **Top-k & threshold** — The code sorts by similarity and keeps matches with score **≥ 0.1** (implementation currently picks **at least one** top document when above threshold; see source for exact loop). If nothing passes the threshold:

   - A record may be inserted into collection **`unknown_queries`** (question + timestamp).
   - The user gets a fixed “not enough information” style message.

5. **Grounded generation** — Retrieved `content` is concatenated into a **Knowledge Base** block. The LLM is instructed to answer **only** from that text and to refuse if the answer is not clearly there. The final answer is produced via **`/api/generate`**.

6. **Non-RAG chat** — `POST .../chat` skips retrieval and uses a shorter **SSRP assistant** system prompt only.

---

## How plain chat works (no RAG)

`askAI` builds a JSON body for Ollama **`POST`** `{ollamaBaseUrl}/api/generate` with the configured chat model, `stream: false`, and a combined prompt (SSRP system text + user message). The **`response`** field from the JSON body is returned to the client.

---

## Data model (MongoDB)

- **`kb_documents`** — KB chunks for SSRP (vessel registration topics). Seeded at startup if empty; embeddings added by `EmbeddingUpdater`.
- **`unknown_queries`** — Optional log of user questions when RAG cannot find a confident match (best effort insert; failures are printed to stderr).

---

## Configuration

`src/main/resources/application.yml` plus **`application-{profile}.yml`**:

- **`application.yml`** — default profile `dev`, server port, context path.
- **`spring.data.mongodb`** / **`spring.ai.ollama`** — defined per profile (`dev` = localhost; `onsite` / `prod` use env vars such as `MONGO_HOST`, `OLLAMA_BASE_URL`).
- **`OllamaServiceImpl`** reads `spring.ai.ollama.*` via `@Value` for `RestTemplate` calls to Ollama.

---

## Testing

- `AiChatApplicationTests` — `@SpringBootTest` **context load** smoke test.

---

## Operational notes

1. **Startup order** — Two `CommandLineRunner` beans (`AiChatApplication` and `EmbeddingUpdater`) run after the context starts. If ordering ever matters strictly (seed before embed), consider `@Order` or consolidating startup logic so seeding always completes before embedding updates.

2. **`VectorSearchService`** — Duplicate cosine logic exists in `OllamaServiceImpl`; consolidating on `VectorSearchService` would reduce duplication.

3. **Model assumptions** — Embeddings and chat both use **`llama3`**; for best RAG quality, embedding and generation models are often chosen to be compatible—follow Ollama docs for your deployment.

---

## Summary

**rag-chat** is a **Spring Boot 3** service that exposes **chat** and **RAG chat** endpoints, uses **MongoDB** for a small knowledge base and unknown-query logging, and uses **Ollama** (`llama3`) for **embedding** and **text generation**, with **OpenAPI/Swagger** for API exploration.
