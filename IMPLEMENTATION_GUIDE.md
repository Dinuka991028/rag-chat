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
| 5. Wire **`LlmChatService`** to **`ChatModel`** only (no hardcoded provider URL/model) | Yes |
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
| 4. **`KnowledgeBaseSeedRunner`** — seeds via **`vectorStore.add`** (**`@Profile("!test")`**) | See Phase 6 |
| 5. **`LlmChatService`** — RAG uses **`VectorStore`** (not direct entity loop) | Yes |
| 6. ~~**`EmbeddingUpdater`**~~ — removed in **Phase 6** (embed at **`VectorStore.add`**) | N/A |
| 7. Startup: **`@Order(1)`** seed only | Yes |

**Note:** If an older **`kb_documents`** collection causes mapping errors, drop it or migrate rows, then restart.

**Checkpoint:** Application starts; KB rows are typed entities. (`@SpringBootTest` with **`test`** profile loads context without Mongo seeding.)

---

## Phase 4 — Custom `VectorStore` for local MongoDB

**Status: done — 2026-03-22**

| Step | Done |
|------|------|
| 1. **`spring-ai-vector-store`** dependency in `pom.xml` | Yes |
| 2. **`LocalMongoVectorStore`** implements **`VectorStore`** (`add`, `similaritySearch`, `delete` by id; filter-delete unsupported) | Yes |
| 3. **`VectorStoreConfig`** exposes **`@Bean` `VectorStore`** | Yes |
| 4. **`LlmChatService`** RAG path uses **`vectorStore.similaritySearch(SearchRequest)`** (threshold 0.1, topK 1) | Yes |
| 5. Embeddings at seed: **`VectorStore.add`** (**Phase 6**) — no separate **`EmbeddingUpdater`** | Yes |
| 6. Removed **`VectorSearchService`** (cosine logic lives in **`LocalMongoVectorStore`**) | Yes |

**Note:** Metadata **filter expressions** on search/delete throw **`UnsupportedOperationException`** until implemented.

**Checkpoint:** Run app with Mongo + Ollama; `/chat/rag` behaves as before after embeddings are populated.

---

## Phase 5 — RAG orchestration with `ChatModel` + `VectorStore`

**Status: done — 2026-03-22**

| Step | Done |
|------|------|
| 1. **`LlmChatService`** injects **`ChatModel`** (provider from **`conf.ai.chat-provider`**) | Yes |
| 2. Plain chat: **`Prompt(SystemMessage, UserMessage)`** + **`chatModel.call`** | Yes |
| 3. RAG: **`VectorStore.similaritySearch`** → **`Prompt`** with **RAG `SystemMessage`** + **`UserMessage`** (excerpts + question) | Yes |
| 4. Response text via **`ChatResponse.getResult().getOutput().getText()`** | Yes |
| 5. **`RestTemplate`** removed from chat path; **`ChatController`** still uses **`AIService`** only | Yes |

**Checkpoint:** `POST /chat` and `POST /chat/rag` use Spring AI **`ChatModel`** end-to-end for generation (no `RestTemplate` / `/api/generate`).

---

## Phase 6 — Startup seeding and embeddings

**Status: done — 2026-03-22**

| Step | Done |
|------|------|
| 1. **`KnowledgeBaseSeedRunner`** builds Spring AI **`Document`** seeds (`text` + `category` / `source` metadata) | Yes |
| 2. **`vectorStore.add(seeds)`** persists **`KnowledgeDocument`** + **embeddings** via **`LocalMongoVectorStore`** | Yes |
| 3. **`EmbeddingUpdater`** **removed** (no second runner) | Yes |
| 4. Single **`CommandLineRunner`** — **`@Order(1)`** on **`KnowledgeBaseSeedRunner`** (**`!test`**) | Yes |

**Checkpoint:** Empty KB → startup inserts all chunks **with embeddings**; RAG works without a separate embedding pass.

---

## Phase 7 — Plug-and-play second LLM (e.g. OpenAI) — **done**

1. **`pom.xml`** — starters on the classpath (same BOM): **`spring-ai-starter-model-ollama`**, **`spring-ai-starter-model-openai`**, **`spring-ai-starter-model-vertex-ai-gemini`**. Spring AI picks **`ChatModel`** / **`EmbeddingModel`** from **`spring.ai.model.chat`** / **`spring.ai.model.embedding`** (**`conf.ai.chat-provider`** / **`conf.ai.embedding-provider`**). **`application.yml`** excludes non-chat OpenAI auto-configurations (speech, transcription, image, moderation) so an empty **`OPENAI_API_KEY`** is valid when chat/embeddings use Ollama; remove those excludes if you enable those APIs and set a key.

2. **Secrets** — **`conf.openai.api-key`** defaults to **`${OPENAI_API_KEY:}`** in base config (empty is fine when both providers are **ollama**). Profile **`openai`** uses **`${OPENAI_API_KEY}`** (required when chat is OpenAI). Never commit keys.

3. **Profiles** — **`application-openai.yml`** (**`openai`**) sets **`conf.ai.chat-provider: openai`**. **`application-vertex-gemini.yml`** (**`vertex-gemini`**) sets **`chat-provider: vertexai`** and GCP **`conf.vertex.gemini.*`**. Activate e.g. **`--spring.profiles.active=dev,openai`** or **`dev,vertex-gemini`**. Keep **`embedding-provider: ollama`** when you want existing **`kb_documents`** vectors unchanged.

4. **Switching embedding to OpenAI** — Change **`conf.ai.embedding-provider`** to **`openai`** and **re-seed** the KB (empty collection or migrate / re-embed), because stored vectors must use the same embedding space as **`EmbeddingModel`**.

5. **Security review:** egress allowlists, logging (no full prompts if policy forbids), API key rotation.

**Checkpoint:** Same service code (**`LlmChatService`** + **`ChatModel`**); only **`conf.ai.*`**, credentials, and optional Spring profiles (**`openai`**, **`vertex-gemini`**, …) change the LLM adapter.

---

## Phase 8 — Cleanup and hardening

1. **Delete** unused classes if any remain; **`VectorSearchService`** already removed (**Phase 4**).
2. **Keep** `AIService` interface only if it still adds clarity; otherwise use `RagChatService` + `PlainChatService` names.
3. **Update** **`ARCHITECTURE.md`** to match the new flow.
4. **Government / security:** TLS to MongoDB, auth, network rules, backup/restore of `kb_documents`, audit trail for admin updates to KB.

---

## Quick reference — what you touch

| Item | Action |
|------|--------|
| `pom.xml` | BOM + Ollama + OpenAI + Vertex Gemini starters (Phase 7); pick provider in **`conf.ai.*`** |
| `application.yml` + `application-*.yml` | `conf:` + `${conf.*}`; Maven `@activatedProperties@`; per-profile `conf` overrides |
| New | `KnowledgeDocument` + repo (**Phase 3**); **`LocalMongoVectorStore`** + **`spring-ai-vector-store`** (**Phase 4**); later: `ChatModel` + slim RAG service (**Phase 5**) |
| Refactor | **`ChatModel`** in **`LlmChatService`** (**Phase 5**) — provider-neutral name |
| Seed | `KnowledgeBaseSeedRunner` → `vectorStore.add` |
| Remove | ~~`EmbeddingUpdater`~~ removed (**Phase 6**) |

---

## If you get stuck

- **Bean conflicts** — Two `ChatModel` beans: use `@Primary`, `@Qualifier`, or one profile.
- **`VectorStore` API changes** — Open `spring-ai-core` Javadoc for your BOM version.
- **Embedding dimensions** — Chat and embedding models must be consistent for stored vectors vs query vectors; changing the embedding model usually requires **re-embedding** all KB rows.

For design rationale (Atlas vs local, patterns), see **`SPRING_AI_DESIGN.md`**.
