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
        → ChatService (interface)
            → LlmChatService
                → ChatModel (Spring AI — provider from config) + VectorStore (LocalMongoVectorStore)
                → MongoTemplate / KnowledgeDocumentRepository → MongoDB
```

Supporting pieces:

- **`KnowledgeBaseSeedRunner`** — **`@Order(1)`**, **`@Profile("!test")`**; if the KB is empty, seeds Spring AI **`Document`** chunks and calls **`vectorStore.add(...)`**, which persists **`KnowledgeDocument`** rows **with embeddings** (via **`EmbeddingModel`** inside **`LocalMongoVectorStore`**).

**`LocalMongoVectorStore`** implements **`VectorStore`** (`add` + `similaritySearch`): cosine search over stored embeddings. RAG uses **`vectorStore.similaritySearch(SearchRequest)`** from **`LlmChatService`**.

---

## Package layout (`ai_chat`)

| Area | Responsibility |
|------|----------------|
| `AiChatApplication` | `@SpringBootApplication` entry point |
| `KnowledgeBaseSeedRunner` | KB seeding `CommandLineRunner` **`@Order(1)`**, profile **`!test`** |
| `domain.KnowledgeDocument` | Typed Mongo entity for **`kb_documents`** |
| `repository.KnowledgeDocumentRepository` | `MongoRepository` for KB CRUD |
| `controller.ChatController` | REST endpoints under `/chat` (full path includes context path, e.g. `/ai-chat/chat`) |
| `service.ChatService` | Contract: `askAI`, `askAIWithContext` |
| `service.impl.LlmChatService` | **`ChatModel`** only (no provider imports) + RAG via **`VectorStore`** |
| `vectorstore.LocalMongoVectorStore` | **`VectorStore`** implementation (local Mongo + cosine search) |
| `config.VectorStoreConfig` | **`VectorStore`** bean |
| `config.OpenApiConfig` | OpenAPI metadata for Swagger |

---

## REST API

| Method & path | Body | Behavior |
|---------------|------|----------|
| `POST /ai-chat/chat` | Raw string (message) | Calls **`askAI`**: system prompt for SSRP + user message → **`ChatModel`** — **no** KB retrieval. |
| `POST /ai-chat/chat/rag` | Raw string (message) | Calls **`askAIWithContext`**: embed query, retrieve similar KB docs, then **generate** with KB-only instructions. |

Swagger/OpenAPI UI is provided by springdoc (with the configured context path, e.g. **`http://localhost:8080/ai-chat/swagger-ui.html`**).

---

## How RAG works (end-to-end)

1. **Knowledge storage** — Documents live in MongoDB collection **`kb_documents`**, with fields such as `content`, `category`, `source`, and **`embedding`** (list of floats), populated on ingest via **`VectorStore.add`**.

2. **Query embedding** — **`LocalMongoVectorStore`** uses **`EmbeddingModel.embed(query)`** for the query vector.

3. **Similarity search** — **`VectorStore.similaritySearch(SearchRequest)`** scores stored embeddings with **cosine similarity**, applies **`similarityThreshold`** (0.1) and **`topK`** (1), returns Spring AI **`Document`** results.

4. **No match** — If nothing passes the threshold (or KB has no embeddings), the flow matches the previous **unknown query** behavior:

   - A record may be inserted into collection **`unknown_queries`** (question + timestamp).
   - The user gets a fixed “not enough information” style message.

5. **Grounded generation** — Retrieved excerpts are sent in a **`UserMessage`**; **`ChatModel`** is called with a dedicated **RAG `SystemMessage`** (KB-only rules). The reply comes from **`ChatResponse`**.

6. **Non-RAG chat** — `POST .../chat` skips retrieval and uses a shorter **SSRP assistant** system prompt only.

---

## How plain chat works (no RAG)

`askAI` calls **`chatModel.call(new Prompt(new SystemMessage(SSRP…), new UserMessage(message)))`** and returns the assistant text from **`ChatResponse`** (no `RestTemplate`).

---

## Data model (MongoDB)

- **`kb_documents`** — KB chunks for SSRP (vessel registration topics). Seeded at startup if empty through **`vectorStore.add`** (embeddings computed at seed time).
- **`unknown_queries`** — Optional log of user questions when RAG cannot find a confident match (best effort insert; failures are printed to stderr).

---

## Configuration

`src/main/resources/application.yml` plus **`application-{profile}.yml`** (SRP-style):

- **`conf:`** — single place for app name, server port/context-path, Mongo (**`MONGODB_URI`** optional for TLS / full connection string), Ollama models/URL, springdoc toggles, logging levels, multipart limits.
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

3. **Model assumptions** — Default **`llama3`** for chat and embeddings when providers are Ollama; OpenAI model names live under **`conf.openai.*`**. Do not mix embedding spaces without re-embedding stored chunks.

---

## Summary

**rag-chat** is a **Spring Boot 3** service that exposes **chat** and **RAG chat** endpoints, uses **MongoDB** with **`KnowledgeDocument`** + **`VectorStore`**, and uses Spring AI **`ChatModel`** + **`EmbeddingModel`** (provider chosen in **`conf.ai.*`**) with **OpenAPI/Swagger** for API exploration.
