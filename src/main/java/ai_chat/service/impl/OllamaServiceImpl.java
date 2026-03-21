package ai_chat.service.impl;

import ai_chat.service.AIService;
import ai_chat.service.VectorSearchService;
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

    @Autowired
    private VectorSearchService vectorSearchService; // ✅ Injected

    // 1️⃣ BASIC AI CALL
    @Override
    public String askAI(String prompt) {
        String url = "http://localhost:11434/api/generate";
        String systemPrompt = "You are an AI assistant for the Small Ship Registry Portal (SSRP) in Bahrain. " +
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

    // 2️⃣ RAG WITH CONTEXT (MAIN LOGIC)
    @Override
    public String askAIWithContext(String prompt) {
        List<Document> docs = mongoTemplate.findAll(Document.class, "kb_documents");
        if (docs.isEmpty()) return "Knowledge base is empty.";

        List<Float> queryEmbedding = getEmbedding(prompt);

        // ✅ Use VectorSearchService to get top 3 docs
        List<Document> topDocs = vectorSearchService.getTopDocuments(docs, queryEmbedding, 3);

        if (topDocs.isEmpty()) {
            // Save unknown queries
            Document unknown = new Document()
                    .append("question", prompt)
                    .append("createdAt", new Date());
            try {
                mongoTemplate.insert(unknown, "unknown_queries");
            } catch (Exception e) {
                System.err.println("Failed to save unknown query: " + e.getMessage());
            }
            return "Sorry, I don’t have enough information to answer that right now. Please contact support or try another question.";
        }

        // Build context
        StringBuilder context = new StringBuilder();
        for (Document doc : topDocs) {
            String content = doc.getString("content");
            if (content != null) context.append(content).append("\n\n");
        }

        String fullPrompt = "You are an AI assistant for the Small Ship Registry Portal (SSRP) in Bahrain.\n" +
                "Answer ONLY using the provided knowledge base.\n" +
                "Do NOT use external knowledge.\n" +
                "If the answer is not clearly available, say: 'Sorry, I don’t have enough information to answer that right now.'\n\n" +
                "Knowledge Base:\n" +
                context +
                "Customer Question: " + prompt;

        try {
            return askAI(fullPrompt);
        } catch (Exception e) {
            System.err.println("Ollama API error: " + e.getMessage());
            return "Sorry, the AI service is temporarily unavailable. Please try again later.";
        }
    }

    // 3️⃣ EMBEDDING GENERATION
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
        for (Double d : embedDoubles) embedding.add(d.floatValue());
        return embedding;
    }
}