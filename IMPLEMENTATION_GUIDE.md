# Step-by-step implementation guide (Spring AI + local MongoDB + swappable LLM)

This guide matches **`SPRING_AI_DESIGN.md`** and targets **self-hosted MongoDB** (no Atlas). Work through the phases in order; verify each phase before moving on.

---

## Phase 0 — Prerequisites

1. **JDK 17** and **Maven** (you already use these).
2. **MongoDB** running where your app can reach it (local or DC); note host, port, database name, credentials.
3. **Ollama** running with a model that supports **chat** and **embeddings** (e.g. `llama3` or the model your security team approves). Test:
   - `POST http://localhost:11434/api/generate`
   - `POST http://localhost:11434/api/embeddings`
4. Back up your current code branch (git tag or branch) so you can compare while refactoring.

---

## Phase 1 — Add Spring AI to Maven

**Status: done — 2026-03-22**

| Step | Done |
|------|------|
| 1. Edit `pom.xml` | Yes |
| 2. Import `spring-ai-bom` **1.0.0** in `<dependencyManagement>` | Yes |
| 3. Add `spring-ai-starter-model-ollama` (no version; BOM manages it) | Yes |
| 4. Skip `spring-ai-starter-vector-store-mongodb-atlas` (local Mongo) | Yes |
| 5. `mvn -q dependency:resolve` | Yes (exit 0) |

**Note:** If `mvn compile` reports class file version errors, Maven may be using an older JDK. Run `mvn -version` and ensure it shows **Java 17+** (set `JAVA_HOME` to your JDK 17 install so it matches `java.version` **17** in the POM).

<details>
<summary>Reference: XML added to <code>pom.xml</code> (already applied in repo)</summary>

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-ollama</artifactId>
</dependency>
```

</details>

---

## Phase 2 — Configure Ollama + server (`application.yml`)

**Status: done — 2026-03-22**

| Step | Done |
|------|------|
| 1. Replace `application.properties` with **`application.yml`** | Yes |
| 2. Set **`spring.ai.ollama`** (base URL, chat + embedding models) | Yes |
| 3. Keep MongoDB under **`spring.data.mongodb`** | Yes |
| 4. Set **`server.port`** and **`server.servlet.context-path`** | Yes (`8080`, `/ai-chat`) |
| 5. Wire **`OllamaServiceImpl`** to `spring.ai.ollama.*` (no hardcoded Ollama URL/model) | Yes |
| 6. Profiles **`dev`**, **`onsite`**, **`prod`** (`application-*.yml`) | Yes |
| 7. **`conf:`** block + **`${conf.*}`** placeholders (SRP-style central config) | Yes |
| 8. Maven **`@activatedProperties@`** → `spring.profiles.active` (`pom.xml` profiles **`dev`** / **`onsite`** / **`prod`**) | Yes |

**URLs:** API base is `http://localhost:8080/ai-chat` (defaults from `conf.server.*`; e.g. `POST /ai-chat/chat`, `POST /ai-chat/chat/rag`). Swagger: `/ai-chat/swagger-ui.html`.

### Spring profiles — **done — 2026-03-22**

| Profile | File | Purpose |
|---------|------|---------|
| **`dev`** (default) | `application-dev.yml` | Local Mongo + Ollama; DEBUG for `ai_chat`; Swagger enabled |
| **`onsite`** | `application-onsite.yml` | On-premises / gov network; Mongo & Ollama via **env** (see below); Swagger enabled |
| **`prod`** | `application-prod.yml` | Production; env-driven URLs/models; **Swagger disabled**; quieter logging |

**Activate Spring profile:**

- **Maven build:** `spring.profiles.active` is set from **`@activatedProperties@`** in `application.yml` (filtered at build time). Use e.g. `mvn package -Pprod` or `-Ponsite` (default Maven profile is **`dev`**).
- **Runtime override:** `java -jar app.jar --spring.profiles.active=prod`
- **IDE:** enable **Delegate build/run to Maven** (or run `mvn process-resources` so `@activatedProperties@` is replaced); otherwise the unfiltered token can break profile activation.

**Environment variables (typical for `onsite` / `prod`):**

| Variable | Meaning |
|----------|---------|
| `MONGO_HOST` | MongoDB host (default `localhost` in yml if unset — set in real deploys) |
| `MONGO_PORT` | MongoDB port (default `27017`) |
| `MONGO_DATABASE` | Database name (default `ai`) |
| `OLLAMA_BASE_URL` | e.g. `http://ollama.internal:11434` |
| `OLLAMA_CHAT_MODEL` | Chat model name |
| `OLLAMA_EMBEDDING_MODEL` | Embedding model name |

<details>
<summary>Reference: layout (SRP-style)</summary>

- **`application.yml`** — wires `spring.*`, `server.*`, `springdoc.*`, `logging.*` from **`${conf.*}`**; bottom section **`conf:`** holds defaults; **`spring.profiles.active: @activatedProperties@`** (Maven-filtered); banner `classpath:banner/banner.txt`
- **`application-dev.yml`** / **`application-onsite.yml`** / **`application-prod.yml`** — override **`conf:`** (and thus all derived settings) per environment
- **`pom.xml`** — `activatedProperties` + Maven **resource filtering** (delimiter **`@` only**, so `${conf...}` is not mangled)

This app does **not** include SRP-only pieces (Keycloak, JPA, SQL Server, mail, NPA, etc.); only the **configuration pattern** is mirrored.

</details>

---

## Phase 3 — Structured MongoDB documents

**Status: done — 2026-03-22**

| Step | Done |
|------|------|
| 1. **`ai_chat.domain.KnowledgeDocument`** — `@Document(collection = "kb_documents")`, Lombok `@Builder` | Yes |
| 2. Fields: `id`, `content`, `category`, `source`, `embedding` (`List<Float>`) | Yes |
| 3. **`ai_chat.repository.KnowledgeDocumentRepository`** — `MongoRepository<KnowledgeDocument, String>` | Yes |
| 4. **`AiChatApplication`** — seed with **`saveAll`** (no raw `org.bson.Document`) | Yes |
| 5. **`OllamaServiceImpl`** — RAG uses **`KnowledgeDocument`** via repository | Yes |
| 6. **`EmbeddingUpdater`** — loads/saves **`KnowledgeDocument`** | Yes |
| 7. Startup order: **`@Order(1)`** seed, **`@Order(2)`** embeddings | Yes |

**Note:** If an older **`kb_documents`** collection causes mapping errors, drop it or migrate rows, then restart so seed + embedding run again.

**Checkpoint:** Application starts; KB rows are typed entities. (`@SpringBootTest` still loads context.)

---

## Phase 4 — Custom `VectorStore` for local MongoDB

**Status: done — 2026-03-22**

| Step | Done |
|------|------|
| 1. **`spring-ai-vector-store`** dependency in `pom.xml` | Yes |
| 2. **`LocalMongoVectorStore`** implements **`VectorStore`** (`add`, `similaritySearch`, `delete` by id; filter-delete unsupported) | Yes |
| 3. **`VectorStoreConfig`** exposes **`@Bean` `VectorStore`** | Yes |
| 4. **`OllamaServiceImpl`** RAG path uses **`vectorStore.similaritySearch(SearchRequest)`** (threshold 0.1, topK 1) | Yes |
| 5. **`EmbeddingUpdater`** uses **`EmbeddingModel.embed`** (no `RestTemplate` / `getEmbedding`) | Yes |
| 6. Removed **`VectorSearchService`** (cosine logic lives in **`LocalMongoVectorStore`**) | Yes |

**Note:** Metadata **filter expressions** on search/delete throw **`UnsupportedOperationException`** until implemented.

**Checkpoint:** Run app with Mongo + Ollama; `/chat/rag` behaves as before after embeddings are populated.

---

## Phase 5 — RAG orchestration with `ChatModel` + `VectorStore`

1. **Create a service** e.g. `RagChatService` (or rename `AIService` implementation) that depends on:

   - `ChatModel` (or `ChatClient` if you use the fluent API — Spring AI supports both),
   - `EmbeddingModel` (only if not fully hidden inside `VectorStore`),
   - `VectorStore`,
   - optional `MongoRepository` for **`unknown_queries`**.

2. **Flow for RAG:**

   - `vectorStore.similaritySearch(SearchRequest.builder().query(userMessage).topK(3).similarityThreshold(0.1).build())`
   - If empty / below threshold → log to `unknown_queries`, return your safe message.
   - Build a **UserMessage** / prompt with “answer only from context” + joined chunk text + user question.
   - Call **`chatModel.call(new Prompt(...))`** or **`ChatClient`** and return the string content.

3. **Flow for non-RAG:** call **`ChatModel`** with your SSRP system prompt + user message only (no `VectorStore`).

4. **Wire** `ChatController` to this service only.

**Checkpoint:** `POST /chat` and `POST /chat/rag` behave like before; Ollama is no longer called via raw `RestTemplate` in business code.

---

## Phase 6 — Startup seeding and embeddings

1. **`AiChatApplication`** — Keep **creation of collection** and **seed data**, but insert **typed entities** or Spring AI **`Document`** instances and call **`vectorStore.add(...)`** instead of raw `Document` + manual embedding in a separate runner.

2. **`EmbeddingUpdater`** — Either:
   - **delete** it if `VectorStore.add` always embeds on ingest, or
   - replace with a one-off migration command that loads documents without `embedding` and calls `vectorStore.add` / repository save with embedding.

3. Ensure **only one** `CommandLineRunner` owns “seed KB” to avoid ordering bugs; use **`@Order(Ordered.HIGHEST_PRECEDENCE)`** on seed, then **`@Order(1)`** on optional post-migration if needed.

**Checkpoint:** Fresh DB: app starts, KB is filled, embeddings exist, RAG answers from KB.

---

## Phase 7 — Plug-and-play second LLM (e.g. OpenAI)

1. Add the starter your org allows, e.g. **`spring-ai-starter-model-openai`** (BOM version only).

2. Add **API keys** via environment variables or a secrets manager — **never** commit keys.

3. Use **`spring.profiles.active=openai`** (example) with:

   - `application-openai.properties` — OpenAI base URL, model names, etc.

4. Use **`@Profile("ollama")`** / **`@Profile("openai")`** on `@Configuration` classes that declare **`@Bean` `ChatModel`** / **`EmbeddingModel`** **only if** auto-configuration does not already switch on properties. Often **property-driven** single starter is enough:

   - Prefer **one active provider per environment** to avoid two primary `ChatModel` beans.

5. **Security review:** endpoints, egress allowlists, logging (no full prompts in logs if policy forbids).

**Checkpoint:** Same `RagChatService` code path; only configuration changes between Ollama and OpenAI in a non-prod environment.

---

## Phase 8 — Cleanup and hardening

1. **Delete** unused classes: old `OllamaServiceImpl` if fully replaced, redundant `VectorSearchService` if logic lives in `LocalMongoVectorStore`.
2. **Keep** `AIService` interface only if it still adds clarity; otherwise use `RagChatService` + `PlainChatService` names.
3. **Update** **`ARCHITECTURE.md`** to match the new flow.
4. **Government / security:** TLS to MongoDB, auth, network rules, backup/restore of `kb_documents`, audit trail for admin updates to KB.

---

## Quick reference — what you touch

| Item | Action |
|------|--------|
| `pom.xml` | BOM + `spring-ai-starter-model-ollama`; optional OpenAI starter later |
| `application.yml` + `application-*.yml` | `conf:` + `${conf.*}`; Maven `@activatedProperties@`; per-profile `conf` overrides |
| New | `KnowledgeDocument` + repo (**Phase 3**); **`LocalMongoVectorStore`** + **`spring-ai-vector-store`** (**Phase 4**); later: `ChatModel` + slim RAG service (**Phase 5**) |
| Refactor | `ChatController` → new service; remove `RestTemplate` from domain |
| Seed | `AiChatApplication` / runner → `vectorStore.add` |
| Remove | `EmbeddingUpdater` or slim to migration only |

---

## If you get stuck

- **Bean conflicts** — Two `ChatModel` beans: use `@Primary`, `@Qualifier`, or one profile.
- **`VectorStore` API changes** — Open `spring-ai-core` Javadoc for your BOM version.
- **Embedding dimensions** — Chat and embedding models must be consistent for stored vectors vs query vectors; changing the embedding model usually requires **re-embedding** all KB rows.

For design rationale (Atlas vs local, patterns), see **`SPRING_AI_DESIGN.md`**.
