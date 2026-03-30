# RAG Learning Notes (Single File)

Study order (recommended):
1. `STUDY 1` vectors/embeddings
2. `STUDY 2` cosine similarity
3. `STUDY 3` similarity search + `topK`
4. `STUDY 4` prompt context + `SYSTEM_RAG`
5. `STUDY 5` full end-to-end RAG flow
6. `STUDY 6` MongoDB storage of embeddings (float arrays)

---

## STUDY 1: Vectors (Embeddings) in This Codebase

Goal: understand what a “vector” is and where your app creates it.

### What is an embedding (vector)?

An **embedding** is a list of numbers that represents the meaning of some text.

Rule of thumb:
* similar meanings -> embeddings are close
* different meanings -> embeddings are far

In this project, the embedding is created by Spring AI’s `EmbeddingModel`.

### Where embeddings are created (ingestion time)

Embeddings are generated when you add knowledge to the KB.

Main path:
* `KnowledgeBaseSeedRunner` / `KnowledgeAdminService`
  -> `vectorStore.add(List<Document>)`
  -> `LocalMongoVectorStore.add(...)`

Core embedding line (in `LocalMongoVectorStore`):

* `float[] vector = embeddingModel.embed(doc.getText());`

Then it stores the vector in Mongo:
* `entity.setEmbedding(toFloatList(vector));`

Mongo document type:
* `ai_chat.domain.KnowledgeDocument`

Field:
* `embedding` (type `List<Float>`)

### Where embeddings are created (query time)

When a user asks a question with RAG, the query is also embedded.

Main path:
* `POST /chat/rag` or `/chat/rag/conversation`
  -> `LlmChatService.retrieveMerged(...)`
  -> `vectorStore.similaritySearch(...)`
  -> `LocalMongoVectorStore.similaritySearch(...)`

Core embedding line:
* `float[] queryVector = embeddingModel.embed(query);`

### Why you store embeddings in MongoDB

You store embeddings so you do not need to re-embed the KB for every question.

At runtime:
1. embed the question
2. compare it to stored vectors
3. return the most similar chunks as context for the LLM

Quick checklist for this topic:
* Embeddings are created by `embeddingModel.embed(...)` in which class?
* In what Mongo field are embeddings stored?
* Do we embed the query at runtime? Where?

---

## STUDY 2: Cosine Similarity (How “closest vectors” are measured)

Goal: understand how the project measures similarity between two vectors.

### What cosine similarity tells you

Cosine similarity compares the **direction** of two vectors (not their magnitude).

Intuition:
* If vectors point in the same “semantic direction”, similarity is higher.
* If vectors point in different directions, similarity is lower.

### Where cosine similarity is implemented

This repo computes similarity inside:
* `ai_chat.vectorstore.LocalMongoVectorStore.cosineSimilarity(...)`

In your code, the similarity formula is:
* `dot / (sqrt(norm1) * sqrt(norm2))`

### How it is used during search

In `LocalMongoVectorStore.similaritySearch(...)`, the code:
1. embeds the user query
2. for each stored KB embedding:
   * computes cosine similarity
3. sorts by similarity desc
4. keeps `topK`

So cosine similarity is the “ranking” function for vector search.

Quick checklist:
* Where is cosine similarity implemented?
* Does the project use MongoDB vector search for similarity?
* Is similarity computed at ingestion time or query time?

---

## STUDY 3: Similarity Search + TopK (retrieval step)

Goal: understand how the system picks the best chunks to show the LLM.

### What “similarity search” returns

It returns a small list of the most relevant knowledge chunks.

In this project, that happens in:
* `LocalMongoVectorStore.similaritySearch(SearchRequest request)`

### Step-by-step behavior in your code

Inside `LocalMongoVectorStore.similaritySearch(...)`:

1. It embeds the user query:
   * `float[] queryVector = embeddingModel.embed(query)`

2. It loads all stored KB chunks:
   * `knowledgeDocumentRepository.findAll()`

3. For each chunk, it computes similarity:
   * `double sim = cosineSimilarity(queryFloats, emb)`

4. It sorts all scored chunks by similarity

5. It returns only:
   * `topK` results (`request.getTopK()`)

### What “topK” means (very important)

If `topK = 8`, then even if the KB has 10,000 chunks, the system only returns the best 8 chunks to build the LLM prompt.

Quick checklist:
* Where does the project embed the query for retrieval?
* Where does it sort results?
* Where is `topK` applied?
* Does MongoDB do the vector similarity here?

---

## STUDY 4: Prompt Building (Context) + `SYSTEM_RAG`

Goal: understand how retrieved chunks become a final instruction for the LLM.

### How context is formatted in this repo

The RAG prompt payload is built by:
* `ai_chat.service.PromptBuilderService.buildRagPayload(found, customerQuestion)`

It creates a string with sections:
* `[CONTEXT]`
* `[QUESTION]`
* `[INSTRUCTIONS]`

For each retrieved document chunk, it appends a bullet:
* `- ` + (optional metadata label) + `text`

If metadata exists, you will see labels like:
* `[source=..., section=...]`

### What the LLM is told to do

In `ai_chat.service.impl.LlmChatService`, RAG mode uses `SYSTEM_RAG`.

`SYSTEM_RAG` includes rules like:
* Use ONLY the knowledge base excerpts in the context payload
* Do NOT use outside knowledge
* If context is insufficient, reply exactly:
  * `Sorry, I don't have enough information to answer that right now.`

### Where the final LLM call happens

The final answer is generated in:
* `LlmChatService.generateRagAnswer(...)`

That method calls:
* `chatModel.call(new Prompt(messages))`

With:
* `SystemMessage(SYSTEM_RAG)`
* `UserMessage(userPayload)` where `userPayload` is built by `PromptBuilderService`

Quick checklist:
* Where does `[CONTEXT]` get created?
* Which class holds `SYSTEM_RAG`?
* What exact message should the model return if the context is insufficient?

---

## STUDY 5: End-to-End RAG Flow (Request to Answer)

Goal: get the full mental picture of how a query becomes an answer in this app.

### 1) API entry points

RAG endpoints:
* `POST /chat/rag`
* `POST /chat/rag/conversation`

Controller:
* `ai_chat.controller.ChatController`

It calls `ChatService` methods:
* `askAIWithContext(...)`
* `askAIWithContextAndHistory(...)`

Implementation:
* `ai_chat.service.impl.LlmChatService`

### 2) Retrieval: find relevant chunks

In `LlmChatService`, retrieval is mainly:
* `retrieveMerged(retrievalQuery)`

That method does:
1. `hybridRetrievalService.retrieve(...)`
2. `rerankingService.rerank(...)`
3. `mergeCuratedWithSrs(...)`

For learning the core idea:
* the important output of retrieval is: `List<Document> found`

### 3) Prompt building: convert chunks into model input

Once you have `found`, the app builds:
* `PromptBuilderService.buildRagPayload(found, customerQuestion)`

This produces the `[CONTEXT] ... [QUESTION] ... [INSTRUCTIONS]` string.

### 4) LLM call: generate final answer

In `LlmChatService.generateRagAnswer(...)`:
* System message = `SYSTEM_RAG`
* User message = prompt payload built above
* call the model via `chatModel.call(...)`

Finally it returns the assistant text.

Note:
* Hybrid retrieval / reranking are “quality layers” on top of the main RAG loop.

Quick checklist:
* Which controller route starts RAG?
* Which service method runs retrieval?
* Where do retrieved chunks become the prompt?
* Where is the model called?

---

## STUDY 6: How MongoDB Stores Embeddings (Numbers Arrays)

Goal: understand what MongoDB stores and why those fields exist.

### 1) Mongo collection name

Embeddings are stored in MongoDB collection:
* `kb_documents`

That comes from:
* `ai_chat.domain.KnowledgeDocument`
  * `@Document(collection = "kb_documents")`

### 2) Mongo document shape (important fields)

In `KnowledgeDocument`, these are key fields:
* `content` (string)
* `category` (string)
* `source` (string)
* `chunkIndex` (integer, optional)
* `sectionHeading` (string, optional)
* `embedding` (List<Float>)  ← this is the “numbers array”
* `embeddingModel` (string tag)
* `embeddingVersion` (string tag)

### 3) Who writes these fields?

The writer is:
* `ai_chat.vectorstore.LocalMongoVectorStore.add(...)`

During `add(...)`, it:
1. embeds the text via `embeddingModel.embed(doc.getText())`
2. converts `float[]` to `List<Float>`
3. saves the `KnowledgeDocument` entity via:
   * `knowledgeDocumentRepository.save(entity)`

### 4) Why store embeddingModel/embeddingVersion?

Embedding models change. If you embed with Model A and then search with Model B, similarity becomes unreliable.

This project prevents mismatches in:
* `LocalMongoVectorStore.isEmbeddingCompatible(...)`

That uses:
* `embeddingModel`
* `embeddingVersion`

Quick checklist:
* Which Mongo collection stores embeddings?
* Which field contains the float array?
* Which class writes those embeddings into Mongo?
* Why are `embeddingModel` and `embeddingVersion` stored?

