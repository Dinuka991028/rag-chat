# Unknown Query Auto Training (Step by Step)

This guide explains how to automatically convert `unknown_queries` into new KB entries in this project.

Current behavior:
- Unknown questions are saved in MongoDB collection `unknown_queries`.
- Admin can list them via `GET /admin/unknown-queries`.
- KB is updated manually via `/admin/knowledge` or `/admin/knowledge/import-pdf`.

Goal:
- Periodically process unknown questions and feed reliable new knowledge into the vector store.

---

## 1) Choose your mode first

Use one of these two modes:

1. **Recommended: Semi-auto (human approval)**
   - System proposes draft Q&A.
   - Admin reviews and approves.
   - Approved items are added to KB.

2. **Full-auto**
   - System adds generated Q&A directly to KB.
   - Faster, but higher risk of wrong answers.

For production, start with semi-auto and switch later if quality is proven.

---

## 2) Add configuration flags

In `src/main/resources/application.yml` under `conf:`, add:

```yml
conf:
  unknown-training:
    enabled: true
    cron: "0 0 * * * *" # every hour
    batch-size: 50
    min-frequency: 2
    auto-approve: false  # true = full-auto
    delete-processed-unknowns: false
```

Notes:
- `min-frequency`: only train questions asked multiple times.
- `auto-approve: false`: safer mode for first rollout.

---

## 3) Create a draft entity for review queue

Add a new MongoDB collection for proposed KB entries (example: `kb_training_drafts`).

Suggested fields:
- `id`
- `question`
- `normalizedQuestion`
- `count` (how many times this question pattern appeared)
- `proposedAnswer`
- `source` (e.g., `unknown-training`)
- `status` (`PENDING`, `APPROVED`, `REJECTED`, `IMPORTED`)
- `createdAt`, `updatedAt`

This keeps generated content auditable before inserting into KB.

---

## 4) Add normalization + deduping

Before training:
- lowercase
- trim spaces
- collapse repeated whitespace
- remove trailing punctuation noise

Group unknown questions by normalized text and count frequency.

Only process groups where `count >= min-frequency`.

---

## 5) Implement scheduled training job

Create a service like `UnknownQueryTrainingService` and run it with `@Scheduled(cron = ...)`.

Flow per run:
1. Read latest unknown queries (batch-size).
2. Normalize + group similar questions.
3. For each eligible group:
   - Generate draft answer only from trusted internal docs.
   - If semi-auto: save draft with `PENDING`.
   - If full-auto: add directly to KB with `KnowledgeAdminService.addTextEntry(...)`.
4. Optionally mark or delete processed unknown rows.

Important:
- Avoid generating from open web.
- Use your existing trusted sources (SRS markdown/PDF, curated docs).

---

## 6) Add admin endpoints for draft approval

Add endpoints in admin controller (suggestion):

- `GET /admin/unknown-training/drafts?status=PENDING`
- `POST /admin/unknown-training/drafts/{id}/approve`
- `POST /admin/unknown-training/drafts/{id}/reject`
- `POST /admin/unknown-training/drafts/{id}/import` (optional if separate from approve)

Approve flow:
1. Load draft.
2. Build `AddKnowledgeRequest` with:
   - `title`: draft question
   - `content`: approved answer
   - `category`: `UnknownTraining`
   - `source`: `unknown-training`
3. Call `KnowledgeAdminService.addTextEntry(...)`.
4. Update draft status to `IMPORTED`.

---

## 7) Keep quality guardrails

Add these checks before import:
- Minimum answer length and readability.
- Block generic replies like "I don't know".
- Reject if answer contains unsupported claims.
- Keep a max imports per run (e.g., 20) to avoid KB flooding.

Also log metrics:
- unknown questions per day
- drafts generated
- approved/rejected
- imported to KB
- post-import answer success rate

---

## 8) Rollout plan (safe)

1. Enable scheduler with `auto-approve: false`.
2. Run for 1-2 weeks and review draft quality.
3. Tune normalization rules and prompt.
4. Enable partial automation for high-confidence drafts only.
5. Move to full-auto only after stable precision.

---

## 9) Minimal implementation checklist

- [ ] Add config block under `conf.unknown-training`.
- [ ] Add draft entity + repository.
- [ ] Add normalization utility.
- [ ] Add scheduler service with grouping logic.
- [ ] Add admin draft review endpoints.
- [ ] Add metrics/logging.
- [ ] Test with synthetic unknown queries.

---

## 10) Test scenarios

1. Same question asked many times -> one grouped draft.
2. Single typo variants -> grouped by normalization.
3. Low-frequency noise -> skipped.
4. Approve draft -> KB count increases.
5. Rejected draft -> never imported.
6. Full-auto mode -> imports without manual step.

---

## Example prompt pattern for draft generation

Use a strict system prompt for draft generation:

- "Use only provided internal context."
- "If context is insufficient, return EMPTY."
- "Do not invent policies/processes."
- "Return concise support-style answer."

If model returns `EMPTY`, do not create draft/import.

---

## Where this fits in current code

- Unknown logging already exists in `LlmChatService`.
- KB insertion already exists in `KnowledgeAdminService.addTextEntry`.
- Admin controller already exists for operational endpoints.

So you only need to add:
- draft workflow objects
- scheduled processor
- approval endpoints

---

If you want, next step I can implement the actual Java classes and endpoints in this repository with semi-auto mode as default.
