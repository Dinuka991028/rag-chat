package ai_chat.service.impl;

import ai_chat.repository.KnowledgeDocumentRepository;
import ai_chat.service.AIService;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/** Plain + RAG chat via Spring AI {@link ChatModel}; provider is chosen only in configuration ({@code conf.ai.chat-provider}). */
@Service
public class LlmChatService implements AIService {

    private static final double RAG_SIMILARITY_THRESHOLD = 0.1;
    private static final int RAG_TOP_K = 1;

    private static final String SYSTEM_PLAIN =
            "You are an AI assistant for the Small Ship Registry Portal (SSRP) in Bahrain. "
                    + "Always respond in a polite, formal, and helpful tone suitable for a government service. "
                    + "Answer clearly and briefly.";

    private static final String SYSTEM_RAG =
            "You are an AI assistant for the Small Ship Registry Portal (SSRP) in Bahrain.\n"
                    + "Answer ONLY using the knowledge base excerpts in the user message.\n"
                    + "Do NOT use external knowledge.\n"
                    + "If the answer is not clearly available in those excerpts, say exactly: "
                    + "Sorry, I don't have enough information to answer that right now.";

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Override
    public String askAI(String prompt) {
        try {
            ChatResponse response = chatModel.call(
                    new Prompt(new SystemMessage(SYSTEM_PLAIN), new UserMessage(prompt)));
            return extractAssistantText(response);
        } catch (Exception e) {
            System.err.println("Chat model error: " + e.getMessage());
            return "Sorry, the AI service is temporarily unavailable. Please try again later.";
        }
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

        String userPayload =
                "Knowledge base excerpts:\n\n" + context + "\nCustomer question: " + prompt;

        try {
            ChatResponse response = chatModel.call(
                    new Prompt(new SystemMessage(SYSTEM_RAG), new UserMessage(userPayload)));
            return extractAssistantText(response);
        } catch (Exception e) {
            System.err.println("Chat model error: " + e.getMessage());
            return "Sorry, the AI service is temporarily unavailable. Please try again later.";
        }
    }

    private static String extractAssistantText(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return "";
        }
        var output = response.getResult().getOutput();
        if (output == null) {
            return "";
        }
        return output.getText();
    }
}
