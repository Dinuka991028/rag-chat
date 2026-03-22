package ai_chat.service.impl;

import ai_chat.repository.KnowledgeDocumentRepository;
import ai_chat.service.ChatService;
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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Plain + RAG chat via Spring AI {@link ChatModel}; provider is chosen only in configuration ({@code conf.ai.chat-provider}). */
@Service
public class LlmChatService implements ChatService {

    /**
     * Fetch a larger pool so short curated FAQ chunks can be merged in even when raw SRS chunks rank higher.
     */
    private static final double RAG_SIMILARITY_THRESHOLD = SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL;
    private static final int RAG_FETCH_POOL = 28;
    /** Max curated FAQ passages to force into context before filling with SRS PDF chunks. */
    private static final int RAG_CURATED_CAP = 5;
    private static final int RAG_TOP_K = 8;

    private static final String SYSTEM_PLAIN =
            "You are an AI assistant for the Small Ship Registry Portal (SSRP) in Bahrain. "
                    + "Always respond in a polite, formal, and helpful tone suitable for a government service. "
                    + "Answer clearly and briefly.";

    private static final String SYSTEM_RAG =
            "You are an AI assistant for the Small Ship Registry Portal (SSRP) in Bahrain.\n"
                    + "Use ONLY the knowledge base excerpts in the user message. Do not use outside knowledge.\n"
                    + "Reply in a formal, concise government-service style. Do not use chit-chat or phrases like \"I'm happy to help\".\n"
                    + "Answer the customer's question directly in a few short sentences.\n"
                    + "If the excerpts do not clearly and directly answer the question, respond ONLY with exactly: "
                    + "Sorry, I don't have enough information to answer that right now.\n"
                    + "Do not invent section numbers, form names, or steps that are not in the excerpts.\n"
                    + "Do not suggest unrelated processes (for example renewal or payment flows) as a workaround when the question was about something else.\n"
                    + "Do not give long hedging answers when the excerpts are missing or only loosely related.";

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

        List<Document> raw = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(prompt)
                        .topK(RAG_FETCH_POOL)
                        .similarityThreshold(RAG_SIMILARITY_THRESHOLD)
                        .build());

        List<Document> found = mergeCuratedWithSrs(raw, RAG_CURATED_CAP, RAG_TOP_K);

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

    /** Curated FAQ chunks use category != SRS; prefer them when present in the similarity pool. */
    private static boolean isCuratedFaqChunk(Document d) {
        Map<String, Object> meta = d.getMetadata();
        if (meta == null) {
            return false;
        }
        Object c = meta.get("category");
        return c != null && !"SRS".equals(String.valueOf(c));
    }

    /**
     * Keeps similarity order within each group: up to {@code curatedCap} FAQ rows first, then SRS rows to fill {@code maxTotal}.
     */
    private static List<Document> mergeCuratedWithSrs(List<Document> scored, int curatedCap, int maxTotal) {
        if (scored == null || scored.isEmpty()) {
            return List.of();
        }
        List<Document> faq = new ArrayList<>();
        List<Document> srs = new ArrayList<>();
        for (Document d : scored) {
            if (isCuratedFaqChunk(d)) {
                faq.add(d);
            } else {
                srs.add(d);
            }
        }
        List<Document> out = new ArrayList<>(maxTotal);
        for (int i = 0; i < Math.min(curatedCap, faq.size()); i++) {
            out.add(faq.get(i));
        }
        for (Document d : srs) {
            if (out.size() >= maxTotal) {
                break;
            }
            out.add(d);
        }
        return out;
    }
}
