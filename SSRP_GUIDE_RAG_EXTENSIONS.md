# SSRP implementation guide → additions for this RAG system

This document maps the **Developer Implementation Guide (AI Assistance, Intelligence, Reporting)** to **concrete capabilities you can add** to the existing **rag-chat** stack (Spring Boot + Spring AI + MongoDB vector RAG). It separates what belongs **inside this service** from what belongs in the **broader SSRP platform** (Angular, relational DB, ship registry domain).

**Current baseline (this repo):** `POST /chat`, `POST /chat/rag` (raw string body); **`POST /chat/conversation`**, **`POST /chat/rag/conversation`** (JSON with `message` + optional `conversationId` for short-term history); KB seeding; Mongo `VectorStore`; unknown-query logging (optional `conversationId` on logged rows); admin KB ingest. The model is **not** wired to live vessel/inspection/payment data.

---

## 1. Core principle from the guide (how RAG fits)

| Guide principle | Implication for this RAG system |
|-----------------|----------------------------------|
| **Database is source of truth** | RAG answers **procedural / document / FAQ** content from the KB. **Operational facts** (vessel status, owner, dues) must come from **validated APIs or injected context**, not from retrieved chunks alone. |
| **Deterministic layer first** | Add **intent routing** and **optional structured context** *before* calling `ChatModel`. RAG remains one path (e.g. `SERVICE_GUIDANCE`), not the only path. |
| **AI never decides authorization** | Any new endpoint must run **after** Spring Security; RAG prompts receive only **already-authorized** text snippets. |

---

## 2. Additions you can make *inside* rag-chat (this backend)

### 2.1 API and contracts

- **`AIRequestDTO` / `AIResponseDTO`** (guide §5.3): Replace or wrap raw `String` body with JSON: `question`, `portalContext` (CUSTOMER / OFFICER / MANAGEMENT / ENFORCEMENT), `screenName`, `entityId`, optional `locale`.
- **Unified orchestration endpoint**, e.g. `POST /api/ai/query`, that delegates to a single **`AIService.process(request)`** instead of only `/chat` and `/chat/rag`.
- **Response envelope**: `answer`, `responseType` (TEXT | REPORT | ERROR), `data` (chart/table payloads when applicable), `warnings`, plus optional **metadata** (`intent`, `deterministicVsAi`, `latencyMs`).

### 2.2 Intent routing and hybrid answers (guide §7)

- **`IntentRouterService`**: Classify questions into `QueryIntent` (e.g. SERVICE_GUIDANCE, APPLICATION_STATUS, VESSEL_RISK_SUMMARY, REPORT_REGISTRATIONS_BY_YEAR, ENFORCEMENT_LOOKUP, GENERAL_ASSISTANT).
- **Rule-based first** (regex/keywords + optional small classifier): Map intents to **RAG vs plain LLM vs “needs external data”** without executing raw SQL from the model.
- **Parameter extraction**: Years, months, vessel numbers, application IDs — passed to **approved** backend methods or forwarded as **display-only** hints in prompts (not free-form SQL).

### 2.3 Prompt layer (guide §6.2–6.3, §14.4)

- **`PromptBuilder` + `PromptTemplateRegistry`** (versioned templates): System instruction, role, screen, **validated data block**, user question, output format — aligned with guide’s safety rules.
- **Per-intent templates**: e.g. `OFFICER_VESSEL_SUMMARY`, `CUSTOMER_SERVICE_GUIDANCE`, `MANAGEMENT_REPORT_SUMMARY`, `ENFORCEMENT_LOOKUP_SUMMARY`.
- **RAG path**: Keep current KB-only `SYSTEM_RAG` behavior for SERVICE_GUIDANCE; **merge** registry templates so “tone + structure” stay consistent across portals.

### 2.4 Provider abstraction (guide §6.1)

- This repo already uses **Spring AI `ChatModel`** (better than raw `RestTemplate`). Formalize an **`AIProvider`-style facade** only if you need non–Spring-AI adapters; otherwise document **`ChatModel` as the single port**.
- **Feature flag**: `ai.enabled` / `ai.rag.enabled` to return deterministic or canned messages when the provider is off (guide: ship deterministic APIs without AI).

### 2.5 Audit and observability (guide §10.2, §12.2)

- Extend beyond **`unknown_queries`**: Persist **AI query audit** — user id (when auth exists), timestamp, portal, screen, **intent**, entity ids touched, **RAG vs non-RAG vs deterministic**, provider status, latency.
- **Metrics**: Counters/histograms per intent; track “unsupported” and fallback rates.

### 2.6 Knowledge base and RAG quality (guide §4.2, §14.5)

- **Curated DTO-style chunks**: Ensure KB documents are **scoped** (customer-safe vs internal) via `category` / metadata filters so enforcement/officer content is not retrieved for customer intents.
- **Metadata filtering** in `SearchRequest` / pre-filter: e.g. only `category=customer_faq` when `portalContext=CUSTOMER`.
- **v1 supported-questions list** (guide §14.5): Encode as **tests** (intent router + golden RAG responses) and/or **FAQ seed** alignment.

### 2.7 Admin and operations (aligns with guide rollout §12)

- You already have **admin KB** paths; add **prompt template version** and **KB version** in responses or `/actuator`/admin for traceability during UAT.

---

## 3. Additions that require *integration* with the wider SSRP (not KB alone)

These follow the guide but **depend on the ship registry’s relational DB and services**. The RAG service should **consume** them as **read-only context**, not replace them.

| Capability (guide) | Role of RAG |
|--------------------|------------|
| **GET `/api/intelligence/vessels/{id}`** etc. (§5.2) | SSRP exposes DTOs; RAG **orchestrator** calls REST client, injects **VesselIntelligenceDTO** text into `PromptBuilder` for OFFICER summaries. |
| **Reporting aggregates** (§7.3) | Report service returns **RegistrationReportDTO**; RAG only **summarizes** the JSON — **no** inventing counts. |
| **Spring Security / roles** (§10.1) | Implemented on SSRP API gateway or monolith; rag-chat either sits **behind** same auth or receives **signed context** from Angular BFF. |
| **Angular chat widget** (§8) | Calls unified `/api/ai/query` with portal + screen + entity id; **not** a change to vector math — **contract + CORS** matter. |

---

## 4. What not to add (per guide) — security

- **No executing model-generated SQL** in production (§7.4). Any “natural language report” must map to **allowlisted** queries or report services.
- **No full PII tables** in prompts; only **field-minimized DTOs** (§4.2, §6.3).
- **No using the model** to decide **who** may see **what** — only **policy-enforced** data in context.

---

## 5. Suggested implementation order (RAG-focused slice)

1. **DTOs + `POST /api/ai/query`** — backward-compatible deprecation of raw string endpoints if needed.  
2. **Intent router + audit** — measurable, testable, no new infra.  
3. **Prompt registry** — versioned templates; wire SERVICE_GUIDANCE to existing RAG with **metadata filters**.  
4. **HTTP clients to SSRP intelligence/report APIs** — inject validated context for officer/management/enforcement intents.  
5. **Auth integration + role tests** — match guide §10–11.  
6. **Performance** — timeouts, caching for stable reference lists; keep prompts small (§10.3).

---

## 6. Definition of done (RAG module subset of guide §13.2)

- [ ] Intent mapped to a **code path** (RAG, plain LLM, or external data + optional summarize).  
- [ ] Answers for factual claims either **cite KB** (RAG) or **match injected DTOs** (deterministic).  
- [ ] Audit record for each assisted query.  
- [ ] Tests: router patterns, prompt serialization, and **no** unauthorized data in mocked prompts.  

---

*Generated to align the standalone **rag-chat** service with the SSRP AI implementation guide; adjust package names (`com.ssrp` vs `ai_chat`) when merging into a single monorepo.*
