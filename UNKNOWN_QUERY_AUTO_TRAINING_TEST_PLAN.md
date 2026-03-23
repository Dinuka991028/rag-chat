# Unknown Query Auto-Training — Full E2E Test Plan

This document describes how to test the complete “unknown_queries -> kb_training_drafts -> admin approval -> kb_documents” workflow in this repository.

It is written to be followed on a local dev environment where:
- MongoDB is running
- Your app is running
- The KB (`kb_documents`) already has embeddings (seeded at startup when empty)

---

## 0) Endpoints & paths (what the test uses)

Base URL (default):
- `http://localhost:8080/ai-chat`

Chat endpoints:
- `POST /chat/rag` (body is a raw string): creates `unknown_queries` when RAG is insufficient
- `POST /chat/rag/conversation` (JSON): same, but includes `conversationId`

Admin draft endpoints (secured by `/admin/**` gate):
- `GET  /admin/unknown-training/drafts?status=PENDING&page=0&size=50`
- `GET  /admin/unknown-training/drafts/pending`
- `POST /admin/unknown-training/drafts/{id}/approve` (imports to KB and sets status `IMPORTED`)
- `POST /admin/unknown-training/drafts/{id}/reject`  (sets status `REJECTED`)
- `POST /admin/unknown-training/drafts/{id}/import`  (same as approve)

---

## 1) Prerequisites

1. Confirm the app runs and has a working Spring AI provider (Ollama/OpenAI/Vertex).
2. Confirm MongoDB connectivity.
3. Confirm the KB has content:
   - `kb_documents` should be non-empty.
   - If it is empty, the scheduler will often generate drafts of poor quality (because vector retrieval context may be empty).

---

## 2) Make the scheduler run fast (for testing)

The scheduler runs via:
- `@Scheduled(cron = "${conf.unknown-training.cron:0 0 * * * *}")`

For testing, temporarily change `conf.unknown-training.cron` in `src/main/resources/application.yml` to something frequent (restart the app afterwards), for example:
- Every minute: `0 */1 * * * *`
- Every 20 seconds (cron syntax depends on your Spring scheduler config; if uncertain, use every minute first): `*/20 * * * * *`

Suggested config for manual approval tests:
- `conf.unknown-training.auto-approve: false`
- `conf.unknown-training.min-frequency: 2` (default)

Recommended for predictable drafts during testing:
- `conf.unknown-training.min-answer-chars: 80` (default)
- `conf.unknown-training.max-drafts-per-run: 20` (default)

---

## 3) Admin gate (X-Admin-Key)

All admin endpoints are protected by `AdminGateFilter` under `/admin/**`.

- If `conf.admin.api-key` is **blank** (default often empty), then you can call admin endpoints without `X-Admin-Key`.
- If it is **non-blank**, include header:
  - `X-Admin-Key: <ADMIN_API_KEY>`

In Windows PowerShell, define:
```powershell
$base = "http://localhost:8080/ai-chat"
$adminKey = "" # set only if you configured conf.admin.api-key to be non-empty
$headers = @{}
if ($adminKey -ne "") { $headers["X-Admin-Key"] = $adminKey }
```

---

## 4) Testing approach A (Recommended): drive via API + approve/reject

Use this when you want to validate the full “real user flow”:
- call `/chat/rag` with an unknown question
- RAG logs into `unknown_queries`
- scheduler drafts into `kb_training_drafts`
- admin approves/rejects

### 4.1 Pick a question that produces `unknown_queries`

Steps:
1. Send a question that the KB likely cannot answer.
2. Repeat it at least `min-frequency` times (default `2`) with the same wording (minor punctuation/spacing differences are normalized anyway).

PowerShell helper:
```powershell
$msg = "What is the hull maintenance schedule requirement for a mooring permit in Atlantis? Please answer briefly."
Invoke-RestMethod -Method Post -Uri "$base/chat/rag" -Body $msg -ContentType "text/plain"
```

Call it twice (or more if needed):
```powershell
Invoke-RestMethod -Method Post -Uri "$base/chat/rag" -Body $msg -ContentType "text/plain"
Invoke-RestMethod -Method Post -Uri "$base/chat/rag" -Body $msg -ContentType "text/plain"
```

Verification:
- After a call (or soon after), check Mongo:
  - `unknown_queries` should gain rows.

### 4.2 Verify scheduler created drafts

Wait until the next scheduler tick (based on your temporarily modified cron).

Then call:
```powershell
Invoke-RestMethod -Method Get -Uri "$base/admin/unknown-training/drafts/pending" -Headers $headers
```

What to expect:
- Response JSON should include `content` array with `KbTrainingDraft` objects.
- Drafts should have:
  - `status = "PENDING"`
  - `question`, `normalizedQuestion`, `count >= 2`
  - `proposedAnswer` non-empty

Also useful:
- `GET /admin/unknown-training/drafts?status=PENDING&page=0&size=50`

### 4.3 Approve a draft and verify KB import

Pick a draft `id` from the `content` array (e.g., `$draftId`).

Approve:
```powershell
$draftId = "<PUT_DRAFT_ID_HERE>"
Invoke-RestMethod -Method Post -Uri "$base/admin/unknown-training/drafts/$draftId/approve" -Headers $headers
```

What to expect:
- Response includes `status: imported`
- In Mongo:
  - The draft status becomes `IMPORTED`
  - `kb_documents` increases in count
  - Imported KB rows should have metadata applied by `KnowledgeAdminService`:
    - category: `UnknownTraining`
    - source: `unknown-training`

### 4.4 Reject a draft and verify it never re-appears for the same normalized question

1. Create (or wait for) another `PENDING` draft for a normalized question.
2. Reject it:
```powershell
$draftId = "<PUT_DRAFT_ID_HERE>"
Invoke-RestMethod -Method Post -Uri "$base/admin/unknown-training/drafts/$draftId/reject" -Headers $headers
```

What to expect:
- Draft status becomes `REJECTED`

Then:
- Wait for another scheduler tick.
- Re-check `GET /admin/unknown-training/drafts/pending`
- You should NOT see a new `PENDING` draft for the same `normalizedQuestion`.

Reason:
- Dedupe in `UnknownQueryTrainingService` treats `REJECTED` as already processed for that normalized question.

---

## 5) Testing approach B (Deterministic): insert unknown queries directly in Mongo

This method is useful if the chat endpoint doesn’t reliably produce `unknown_queries` for your chosen test question.

### 5.1 Insert repeated unknown queries

In Mongo shell (`mongosh`):
```javascript
use ai

db.unknown_queries.insertMany([
  { question: "Can you explain the SSRP portal login verification steps?", createdAt: new Date() },
  { question: "Can you explain the SSRP portal login verification steps?", createdAt: new Date() }
])
```

Notes:
- Use the same `question` at least `min-frequency` times.
- You can include `conversationId` if you want to test conversation-specific rows; the scheduler doesn’t require it.

### 5.2 Run scheduler and check draft creation

Wait for scheduler tick, then:
```javascript
use ai
db.kb_training_drafts.find({ status: "PENDING" }).sort({ createdAt: -1 }).limit(10).pretty()
```

### 5.3 Approve and verify KB import

Approve via API as in section 4.3 and verify:
```javascript
db.kb_documents.find({ category: "UnknownTraining", source: "unknown-training" }).count()
```

---

## 6) Verify the guardrails behavior

The scheduler rejects draft answers that appear generic/empty or are too short.

How to test:
1. Use a question that results in draft generation returning:
   - `EMPTY`, or
   - a generic response containing phrases like:
     - `I don't know`
     - `Sorry`
     - `not enough information`
2. Confirm:
   - No `PENDING` draft is created, OR
   - Only drafts that pass guardrails appear as `PENDING`

Because guardrails include:
- `min-answer-chars` (default 80)
- sentence/word heuristics

You may need 1-2 tries to find a question that exercises the rejection path.

---

## 7) Test caps (anti-flood)

Caps are configured by:
- `conf.unknown-training.max-drafts-per-run` (semi-auto)
- `conf.unknown-training.max-imports-per-run` (full-auto)

To test `max-drafts-per-run`:
1. Insert many unknown query patterns (different `question` strings) into `unknown_queries` so they normalize to multiple groups.
2. Temporarily set `min-frequency` to something small for quick grouping (keep it >= 2 if you want grouping effects).
3. Set `max-drafts-per-run` to a small number (e.g., `3`).
4. Wait for scheduler tick and verify:
   - `kb_training_drafts` gets at most 3 new `PENDING` drafts in that run.

---

## 8) Optional: test deletion lifecycle

If you enable:
- `conf.unknown-training.delete-processed-unknowns: true`

The scheduler will delete only the `unknown_queries` rows that contributed to patterns that were actually processed (draft created or import created), not the entire fetched batch.

How to test:
1. Set delete-processed-unknowns to `true`
2. Insert 2+ unknown rows for one normalized question.
3. Wait for scheduler.
4. Verify:
   - the contributing `unknown_queries` rows are deleted
   - other unknown rows in the batch (that were dedupe-skipped or guardrail-rejected) remain

---

## 9) What “success” looks like (quick checklist)

After running the pipeline end-to-end in semi-auto mode:
- `unknown_queries` contains the repeated question(s)
- scheduler creates entries in `kb_training_drafts` with `status=PENDING`
- admin can approve them:
  - draft becomes `IMPORTED`
  - KB grows in `kb_documents`
- admin can reject them:
  - draft becomes `REJECTED`
  - scheduler does not create a new `PENDING` draft for the same `normalizedQuestion`

---

## 10) Troubleshooting

1. **No drafts appear**
   - Check `unknown_queries` rows exist and their `question` normalizes into groups with `count >= min-frequency`.
   - Ensure scheduler cron is frequent for testing.
   - Ensure KB embeddings exist in `kb_documents`.

2. **Drafts appear but approval fails**
   - Admin approve enforces basic guardrails (length and generic phrases).
   - Increase `min-answer-chars` temporarily down (e.g., 20) only for testing, then restore.

3. **Drafts keep regenerating after reject**
   - Ensure the rejected draft has the correct `normalizedQuestion`.
   - Dedupe now treats `REJECTED` as processed; if you still see re-generation, confirm you aren’t changing the question wording so its normalized form changes.

