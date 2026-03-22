# Spring AI migration, structured MongoDB, and plug-and-play LLMs

This guide suggests how to evolve **rag-chat** toward **Spring AI**, a **proper vector store**, **cleaner MongoDB modeling**, and **swappable LLM providers** using idiomatic Spring and common design patterns.

---

## 1. Vector store with Spring AI

### What Spring AI gives you

- **`VectorStore`** — add documents, **`similaritySearch(SearchRequest)`** with `topK`, `similarityThreshold`, and metadata filters.
- **`EmbeddingModel`** — turns text into vectors (replaces hand-rolled `RestTemplate` calls to `/api/embeddings`).
- **`Document`** (`org.springframework.ai.document.Document`) — `content` + **metadata** `Map` (category, source, etc.), aligned with how vector DBs expect data.

### Important constraint: MongoDB in Spring AI

The official Spring AI integration is **`spring-ai-starter-vector-store-mongodb-atlas`**: it targets **MongoDB Atlas Vector Search** (`$vectorSearch`, vector index on the cluster). It is **not** the same as “plain” MongoDB Community on `localhost` with manual cosine similarity in Java.

**Choose one path:**

| Approach | When to use |
|----------|-------------|
| **A. MongoDB Atlas + Spring AI MongoDB Atlas vector store** | You want native KNN, scale, and the path [documented by Spring](https://docs.spring.io/spring-ai/reference/api/vectordbs/mongodb.html). Use an Atlas URI in `spring.data.mongodb.uri`, create/configure the vector index (or `spring.ai.vectorstore.mongodb.initialize-schema=true` to opt in to schema init). |
| **B. Keep local MongoDB, implement `VectorStore`** | You must stay on self-hosted Mongo without Atlas vector search. Wrap your existing cosine logic + `MongoTemplate` in a class that implements **`VectorStore`**, so the rest of the app still speaks Spring AI’s API. |
| **C. Different backend** | Use **`VectorStore`** implementations backed by **PostgreSQL + pgvector**, **Redis**, **Chroma**, etc., if you prefer those ops models. Same RAG code; swap the bean. |

**Recommendation:** For “latest Spring AI” and minimal custom code, **Atlas (A)** is the intended stack. If you **cannot or must not use Atlas** (see below), **(B)** or **(C)** keeps architecture clean while still using `EmbeddingModel` + `ChatModel` from Spring AI.

### Government / high-security deployments: yes, you can use local MongoDB

**Atlas** is MongoDB’s **managed cloud** service. Many government or regulated projects require **data residency**, **no public-cloud data stores**, **air-gapped networks**, or **on-premises-only** infrastructure. In those cases **you should not depend on Atlas** for the vector store.

**What to do instead:**

| Need | Approach |
|------|----------|
| **Self-hosted MongoDB** (VM or bare metal in your DC) | Store KB documents and embeddings in **local/on-prem MongoDB** as you do today. Spring AI’s **official** Mongo vector starter targets **Atlas Vector Search only**, so use **path B**: implement **`VectorStore`** yourself (wrap `MongoTemplate` + cosine or your chosen similarity), or use a **different** on-prem `VectorStore` (**path C**, e.g. PostgreSQL + pgvector inside your network). |
| **Still use Spring AI for LLM/embeddings** | Keep **`ChatModel`** / **`EmbeddingModel`** (Ollama on-prem, or an approved API gateway to GPT—per your security review). The vector layer stays swappable. |
| **Security hardening** | TLS to MongoDB, auth (`SCRAM`, x.509 if required), network isolation, encryption at rest (disk/DB config), audit logging, least-privilege DB users, and separate policies for **PII** vs **public KB** text. |

So: **yes, you can store everything in local/self-hosted MongoDB** for a government-style project; you simply **do not use** the Atlas vector starter for that tier—you use **structured collections on your own Mongo** plus a **custom or alternative `VectorStore`**, while still benefiting from Spring AI for models and RAG orchestration.

### Dependencies (conceptual)

Add the **Spring AI BOM** and then:

- `spring-ai-starter-model-ollama` — provides **`ChatModel`** + **`EmbeddingModel`** for Ollama.
- `spring-ai-starter-vector-store-mongodb-atlas` — only if you use **Atlas** vector search.

Use the versions and BOM coordinates from the current [Spring AI getting started](https://docs.spring.io/spring-ai/reference/getting-started.html) (artifact names have changed across milestones; always check upgrade notes).

---

## 2. More structured data in MongoDB

Today you use raw **`org.bson.Document`**. Prefer **explicit domain types** and optional repositories.

### Option A — Domain entity for “your” KB rows (recommended for SSRP-specific fields)

```text
@Document(collection = "kb_documents")
class KnowledgeChunk {
    @Id
    private String id;
    private String content;
    private String category;
    private String source;
    private List<Double> embedding;   // or float[] — align with Atlas index / Spring AI
    private Instant createdAt;
    // getters/setters or Lombok
}
```

- Inject **`MongoRepository<KnowledgeChunk, String>`** or `MongoTemplate` with **`KnowledgeChunk`** instead of `Document`.
- Validation (`@NotBlank`, etc.) and **single place** for field names reduce bugs.

### Option B — Spring AI `Document` + vector store only

If all retrieval goes through **`VectorStore`**, you may store **`org.springframework.ai.document.Document`** (text + metadata) via `vectorStore.add(...)`. Metadata holds `category`, `source`. The **Atlas** store persists the shape Spring AI expects (`content`, `metadata`, embedding path).

### Option C — Hybrid

- **Operational / reporting** data (e.g. **`unknown_queries`**) as a dedicated **`@Document`** entity + repository.
- **KB chunks** managed by **`VectorStore`** after migration to Atlas (or your custom `VectorStore`).

**Avoid** duplicating the same chunk in two shapes; pick one source of truth for embeddings.

---

## 3. Plug-and-play LLM: best-matching patterns

Spring already solves most of this with **interfaces + beans**. These patterns map cleanly:

### Primary pattern: **Ports and adapters (hexagonal)**

| Port (your code depends on this) | Adapter (infrastructure) |
|----------------------------------|---------------------------|
| **`ChatModel`** | `OllamaChatModel`, `OpenAiChatModel`, `AzureOpenAiChatModel`, … |
| **`EmbeddingModel`** | `OllamaEmbeddingModel`, `OpenAiEmbeddingModel`, … |
| **`VectorStore`** | `MongoDBAtlasVectorStore`, your custom Mongo adapter, `PgVectorStore`, … |

Your **application service** (RAG orchestration) should depend only on these **abstractions**, not on `RestTemplate` or URLs.

### **Strategy** (same interface, interchangeable algorithms)

`ChatModel` and `EmbeddingModel` **are** strategy interfaces: at runtime Spring injects the implementation chosen by configuration.

### **Factory / configuration** (which strategy is active)

Use **Spring Boot auto-configuration** + **`@ConditionalOnProperty`** or **`@Profile`** so only one provider’s starters are active, for example:

```properties
# Example idea — exact keys depend on Spring AI version
spring.ai.model.chat=openai
spring.ai.model.embedding=openai
```

or profiles: `dev` → Ollama, `prod` → Azure OpenAI.

You rarely need a hand-written “abstract factory” class; **`@Bean` methods** + **`@ConfigurationProperties`** act as the factory.

### **Facade (optional)**

A thin **`ChatService`** or **`RagService`** that composes `ChatModel`, `EmbeddingModel`, and `VectorStore` behind one method (`answer(userMessage)`) keeps controllers dumb and tests easy (mock the three ports).

### **Anti-pattern to avoid**

A single giant class with `if (provider == OLLAMA)` branches. Prefer **one bean per provider** or **starters** so the **container** selects the implementation.

---

## 4. Target architecture (after refactor)

```text
ChatController
    → RagApplicationService (or ChatFacade)
        → VectorStore.similaritySearch(...)
        → ChatModel.call(...) or ChatClient (Spring AI)
        → (optional) MongoRepository for unknown_queries / audit

Beans:
    EmbeddingModel   ← Ollama / OpenAI / …
    ChatModel        ← Ollama / OpenAI / …
    VectorStore      ← Atlas / custom / pgvector
```

- **Remove** direct `RestTemplate` calls to Ollama from business logic; keep them only inside a custom adapter if you must.
- **Remove** duplicate cosine logic from the chat service once **`VectorStore`** handles search (or centralize in your custom `VectorStore`).

---

## 5. Practical migration order

1. Add Spring AI BOM + **`spring-ai-starter-model-ollama`**; replace `askAI` / embeddings with **`ChatModel`** + **`EmbeddingModel`**.
2. Introduce **`KnowledgeChunk`** (or Spring AI `Document`) and stop using raw `Document` for new code.
3. Decide **Atlas vs local Mongo**: integrate **`MongoDBAtlasVectorStore`** **or** implement **`VectorStore`** for your current DB.
4. Move RAG into one service using **`VectorStore`** + **`ChatModel`**; delete dead **`VectorSearchService`** or wire it only inside your adapter.
5. Add **`spring-ai-starter-model-openai`** (or another) as a second dependency; switch via **`application.properties`** / profiles and verify only one **`ChatModel`**/`**EmbeddingModel**` is primary per environment.

---

## 6. Summary

| Goal | Approach |
|------|----------|
| **Spring AI vector store** | Use **`VectorStore`** + **`EmbeddingModel`**; for Mongo, prefer **Atlas** + official starter, or implement **`VectorStore`** for local Mongo. |
| **Structured MongoDB** | **`@Document` entities** + repositories; align embedding field with Atlas index or Spring AI defaults. |
| **Plug-and-play LLM** | Depend on **`ChatModel`** / **`EmbeddingModel`**; swap Ollama ↔ OpenAI via **dependencies + properties/profiles** — this is **ports & adapters** + **Strategy**, which matches Spring’s DI model better than custom singleton factories. |

For official RAG examples with MongoDB, see the [MongoDB developer tutorial linked from Spring AI’s MongoDB vector docs](https://docs.spring.io/spring-ai/reference/api/vectordbs/mongodb.html).

**Hands-on sequence:** follow **`IMPLEMENTATION_GUIDE.md`** in the same folder for phased steps (Maven, config, entities, custom `VectorStore`, RAG service, profiles, cleanup).
