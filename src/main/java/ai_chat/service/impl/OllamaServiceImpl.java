package ai_chat.service.impl;

import ai_chat.repository.KnowledgeDocumentRepository;
import ai_chat.service.AIService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class OllamaServiceImpl implements AIService {

    private static final double RAG_SIMILARITY_THRESHOLD = 0.1;
    private static final int RAG_TOP_K = 1;

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${spring.ai.ollama.chat.options.model:llama3}")
    private String chatModel;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    private String ollamaApiPath(String path) {
        String base = ollamaBaseUrl.endsWith("/")
                ? ollamaBaseUrl.substring(0, ollamaBaseUrl.length() - 1)
                : ollamaBaseUrl;
        return base + path;
    }

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

    @Override
    public String askAIWithContext(String prompt) {

        if (knowledgeDocumentRepository.count() == 0) {
            return "Knowledge base is empty.";
        }

        List<Document> found = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(prompt)
                        .topK(RAG_TOP_K)
                        .similarityThreshold(RAG_SIMILARITY_THRESHOLD)
                        .build());

        if (found == null || found.isEmpty()) {
            org.bson.Document unknown = new org.bson.Document();
            unknown.put("question", prompt);
            unknown.put("createdAt", new Date());

            try {
                mongoTemplate.insert(unknown, "unknown_queries");
            } catch (Exception e) {
                System.err.println("Failed to save unknown query: " + e.getMessage());
            }

            return "Sorry, I don’t have enough information to answer that right now. Please contact support or try another question.";
        }

        StringBuilder context = new StringBuilder();
        for (Document d : found) {
            String text = d.getText();
            if (text != null) {
                context.append(text).append("\n\n");
            }
        }

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
            System.err.println("Ollama API error: " + e.getMessage());
            return "Sorry, the AI service is temporarily unavailable. Please try again later.";
        }
    }
}
