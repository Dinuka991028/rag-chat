package ai_chat.service.impl;

import ai_chat.domain.KnowledgeDocument;
import ai_chat.repository.KnowledgeDocumentRepository;
import ai_chat.service.AIService;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class OllamaServiceImpl implements AIService {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${spring.ai.ollama.chat.options.model:llama3}")
    private String chatModel;

    @Value("${spring.ai.ollama.embedding.options.model:llama3}")
    private String embeddingModel;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    private String ollamaApiPath(String path) {
        String base = ollamaBaseUrl.endsWith("/")
                ? ollamaBaseUrl.substring(0, ollamaBaseUrl.length() - 1)
                : ollamaBaseUrl;
        return base + path;
    }

    // ✅ 1. BASIC AI CALL (WITH SSRP SYSTEM PROMPT)
    @Override
    public String askAI(String prompt) {

        String url = ollamaApiPath("/api/generate");

        String systemPrompt =
                "You are an AI assistant for the Small Ship Registry Portal (SSRP) in Bahrain. " +
                        "Always respond in a polite, formal, and helpful tone suitable for a government service. " +
                        "Answer clearly and briefly.";

        Map<String, Object> request = new HashMap<>();
        request.put("model", chatModel);
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

        List<KnowledgeDocument> docs = knowledgeDocumentRepository.findAll();

        if (docs.isEmpty()) {
            return "Knowledge base is empty.";
        }

        List<Float> queryEmbedding = getEmbedding(prompt);

        List<ScoredDoc> scoredDocs = new ArrayList<>();

        for (KnowledgeDocument doc : docs) {
            List<Float> embedding = doc.getEmbedding();
            if (embedding == null || embedding.isEmpty()) continue;

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

        scoredDocs.sort((a, b) -> Double.compare(b.score, a.score));

        List<KnowledgeDocument> topDocs = new ArrayList<>();
        double bestScore = 0.0;
        for (ScoredDoc sd : scoredDocs) {
            if (sd.score < 0.1) break;
            if (topDocs.isEmpty()) bestScore = sd.score;
            topDocs.add(sd.doc);
            if (topDocs.size() >= 1) break;
        }

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
        for (KnowledgeDocument d : topDocs) {
            String content = d.getContent();
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
        KnowledgeDocument doc;

        ScoredDoc(double score, KnowledgeDocument doc) {
            this.score = score;
            this.doc = doc;
        }
    }

    // ✅ 3. EMBEDDING GENERATION
    public List<Float> getEmbedding(String text) {

        String url = ollamaApiPath("/api/embeddings");

        Map<String, Object> request = new HashMap<>();
        request.put("model", embeddingModel);
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