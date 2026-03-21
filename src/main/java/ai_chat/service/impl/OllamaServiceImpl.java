package ai_chat.service.impl;

import ai_chat.service.AIService;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class OllamaServiceImpl implements AIService {

    private final RestTemplate restTemplate = new RestTemplate();

    @Autowired
    private MongoTemplate mongoTemplate;

    // ✅ 1. BASIC AI CALL (WITH SSRP SYSTEM PROMPT)
    @Override
    public String askAI(String prompt) {

        String url = "http://localhost:11434/api/generate";

        String systemPrompt =
                "You are an AI assistant for the Small Ship Registry Portal (SSRP) in Bahrain. " +
                        "Always respond in a polite, formal, and helpful tone suitable for a government service. " +
                        "Answer clearly and briefly.";

        Map<String, Object> request = new HashMap<>();
        request.put("model", "llama3");
        request.put("prompt", systemPrompt + "\n\nCustomer: " + prompt);
        request.put("stream", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

        return response.getBody().get("response").toString();
    }

    // ✅ 2. RAG WITH CONTEXT (MAIN LOGIC)
    @Override
    public String askAIWithContext(String prompt) {

        List<Document> docs = mongoTemplate.findAll(Document.class, "kb_documents");

        if (docs.isEmpty()) {
            return "Knowledge base is empty.";
        }

        List<Float> queryEmbedding = getEmbedding(prompt);

        // ✅ Use list to handle duplicates safely
        List<ScoredDoc> scoredDocs = new ArrayList<>();

        for (Document doc : docs) {
            List<Number> embeddingNums = (List<Number>) doc.get("embedding");
            if (embeddingNums == null) continue;

            List<Float> embedding = new ArrayList<>();
            for (Number n : embeddingNums) {
                embedding.add(n.floatValue());
            }

            if (embedding.size() != queryEmbedding.size()) continue;

            double dot = 0.0, norm1 = 0.0, norm2 = 0.0;
            for (int i = 0; i < embedding.size(); i++) {
                dot += queryEmbedding.get(i) * embedding.get(i);
                norm1 += queryEmbedding.get(i) * queryEmbedding.get(i);
                norm2 += embedding.get(i) * embedding.get(i);
            }
            double cosineSim = dot / (Math.sqrt(norm1) * Math.sqrt(norm2));
            scoredDocs.add(new ScoredDoc(cosineSim, doc));
        }

        // Sort descending by similarity
        scoredDocs.sort((a, b) -> Double.compare(b.score, a.score));

        // Pick top 3 docs above threshold
        List<Document> topDocs = new ArrayList<>();
        double bestScore = 0.0;
        for (ScoredDoc sd : scoredDocs) {
            if (sd.score < 0.1) break;
            if (topDocs.isEmpty()) bestScore = sd.score;
            topDocs.add(sd.doc);
            if (topDocs.size() >= 1) break;
        }

        // ❗ Fallback if no good match
        if (topDocs.isEmpty() || bestScore < 0.1) {
            Document unknown = new Document();
            unknown.put("question", prompt);
            unknown.put("createdAt", new Date());

            try {
                mongoTemplate.insert(unknown, "unknown_queries");
            } catch (Exception e) {
                // Log insertion failure but don't fail
                System.err.println("Failed to save unknown query: " + e.getMessage());
            }

            return "Sorry, I don’t have enough information to answer that right now. Please contact support or try another question.";
        }

        // Build context (limit to 3 docs to avoid token overflow)
        StringBuilder context = new StringBuilder();
        for (Document d : topDocs) {
            String content = d.getString("content");
            if (content != null) context.append(content).append("\n\n");
        }

        // Strong SSRP-controlled prompt
        String fullPrompt =
                "You are an AI assistant for the Small Ship Registry Portal (SSRP) in Bahrain.\n" +
                        "Answer ONLY using the provided knowledge base.\n" +
                        "Do NOT use external knowledge.\n" +
                        "If the answer is not clearly available, say: 'Sorry, I don’t have enough information to answer that right now.'\n\n" +
                        "Knowledge Base:\n" +
                        context +
                        "Customer Question: " + prompt;

        try {
            return askAI(fullPrompt);
        } catch (Exception e) {
            // Fallback if Ollama API fails
            System.err.println("Ollama API error: " + e.getMessage());
            return "Sorry, the AI service is temporarily unavailable. Please try again later.";
        }
    }

    // Helper class to safely hold score + document
    private static class ScoredDoc {
        double score;
        Document doc;

        public ScoredDoc(double score, Document doc) {
            this.score = score;
            this.doc = doc;
        }
    }

    // ✅ 3. EMBEDDING GENERATION
    public List<Float> getEmbedding(String text) {

        String url = "http://localhost:11434/api/embeddings";

        Map<String, Object> request = new HashMap<>();
        request.put("model", "llama3");
        request.put("prompt", text);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

        List<Double> embedDoubles = (List<Double>) response.getBody().get("embedding");

        List<Float> embedding = new ArrayList<>();
        for (Double d : embedDoubles) {
            embedding.add(d.floatValue());
        }

        return embedding;
    }
}