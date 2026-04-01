# UI Chat API Guide (Customer + Officer)

Use this file as your speaking notes for tomorrow's team presentation.

## 1) What We Built

We have one chat UI, but it supports two usage modes:

- **Customer chat**: public/customer-facing answers.
- **Officer chat**: internal staff/officer answers.

Both modes call the same backend conversation API, and the mode is selected by the `role` field in the request body.

---

## 2) Main API You Should Show in UI Demo

### Endpoint

`POST /chat/rag/conversation`

This is the endpoint used for:

- RAG (retrieval from knowledge base)
- short-term conversation memory
- role-based behavior (`customer` or `officer`)

### Request Body

```json
{
  "conversationId": "optional-previous-id",
  "message": "How do I renew my ship registration?",
  "role": "customer"
}
```

### Response Body

```json
{
  "conversationId": "a8f12345-4d31-45f3-98ea-abcde1234567",
  "reply": "To renew your ship registration, submit the renewal request with required documents..."
}
```

**Important:** Keep sending the same `conversationId` for follow-up messages so context is preserved.

---

## 3) The Two API Modes You Can Explain

## A) Customer Chat API Mode

Use:

- `POST /chat/rag/conversation`
- with `"role": "customer"` (or omit role; default behaves as customer mode)

Purpose:

- answer customer-facing questions
- simple, clear, policy-safe responses
- retrieves from customer-appropriate KB content

Example:

```json
{
  "conversationId": null,
  "message": "What are the required documents?",
  "role": "customer"
}
```

---

## B) Officer Chat API Mode

Use:

- `POST /chat/rag/conversation`
- with `"role": "officer"`

Purpose:

- internal operations assistant for officers
- can use officer knowledge data
- includes stronger privacy/safety handling in the flow

Example:

```json
{
  "conversationId": null,
  "message": "Show internal steps for reviewing this task type",
  "role": "officer"
}
```

---

## 4) Quick Flow to Explain on One Slide

1. User types message in UI chat.
2. UI sends request to `POST /chat/rag/conversation`.
3. Backend reads `role` (`customer` or `officer`).
4. Backend retrieves relevant KB chunks for that role.
5. LLM generates answer with conversation context.
6. Backend returns `reply` + `conversationId`.
7. UI stores `conversationId` and uses it for next message.

---

## 5) Optional Secondary Endpoints (If Team Asks)

- `POST /chat` -> simple LLM call without RAG/history.
- `POST /chat/rag` -> RAG answer, no conversation memory.
- `POST /chat/conversation` -> conversation memory, no RAG.

For the product demo, focus on **`/chat/rag/conversation`** only.

---

## 6) Suggested Live Demo Script (2-3 minutes)

1. Open UI and send customer question with role = `customer`.
2. Ask a follow-up ("What about fees?") using same conversation.
3. Switch role to `officer`.
4. Ask an internal-process question.
5. Highlight: same UI, same API path, different role behavior.

Closing line:

"We standardized on one chat conversation API and used `role` to separate customer vs officer behavior, which keeps frontend integration simple and scalable."

