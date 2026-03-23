package ai_chat.service.impl;

import ai_chat.domain.UnknownQuery;
import ai_chat.dto.ChatConversationResponse;
import ai_chat.repository.KnowledgeDocumentRepository;
import ai_chat.repository.UnknownQueryRepository;
import ai_chat.service.ChatService;
import ai_chat.service.ConversationHistoryService;
import ai_chat.service.HybridRetrievalService;
import ai_chat.service.PromptBuilderService;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** Short follow-ups (e.g. "yes") get combined with the previous user line for embedding search only. */
    private static final int RETRIEVAL_QUERY_COMBINE_MAX_LEN = 80;

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
    private UnknownQueryRepository unknownQueryRepository;

    @Autowired
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Autowired
    private ConversationHistoryService conversationHistoryService;

    @Autowired
    private PromptBuilderService promptBuilderService;

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

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
        List<Document> found = retrieveMerged(prompt);
        if (found.isEmpty()) {
            logUnknownQuery(prompt, null);
            return "Sorry, I don’t have enough information to answer that right now. Please contact support or try another question.";
        }
        String reply = generateRagAnswer(found, prompt, List.of());
        if (isRagInsufficientReply(reply)) {
            logUnknownQuery(prompt, null);
        }
        return reply;
    }

    @Override
    public ChatConversationResponse askAIWithHistory(String conversationId, String message) {
        String id = conversationHistoryService.resolveOrCreateConversationId(conversationId);
        List<Message> history = conversationHistoryService.snapshot(id);
        String reply;
        try {
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(SYSTEM_PLAIN));
            messages.addAll(history);
            messages.add(new UserMessage(message));
            ChatResponse response = chatModel.call(new Prompt(messages));
            reply = extractAssistantText(response);
        } catch (Exception e) {
            System.err.println("Chat model error: " + e.getMessage());
            reply = "Sorry, the AI service is temporarily unavailable. Please try again later.";
        }
        conversationHistoryService.append(id, message, reply);
        return new ChatConversationResponse(id, reply);
    }

    @Override
    public ChatConversationResponse askAIWithContextAndHistory(String conversationId, String message) {
        String id = conversationHistoryService.resolveOrCreateConversationId(conversationId);
        List<Message> history = conversationHistoryService.snapshot(id);

        if (knowledgeDocumentRepository.count() == 0) {
            String reply = "Knowledge base is empty.";
            conversationHistoryService.append(id, message, reply);
            return new ChatConversationResponse(id, reply);
        }

        String retrievalQuery = buildRetrievalQuery(history, message);
        List<Document> found = retrieveMerged(retrievalQuery);
        String reply;
        if (found.isEmpty()) {
            logUnknownQuery(message, id);
            reply =
                    "Sorry, I don’t have enough information to answer that right now. Please contact support or try another question.";
        } else {
            reply = generateRagAnswer(found, message, history);
            if (isRagInsufficientReply(reply)) {
                logUnknownQuery(message, id);
            }
        }
        conversationHistoryService.append(id, message, reply);
        return new ChatConversationResponse(id, reply);
    }

    private List<Document> retrieveMerged(String retrievalQuery) {
        List<Document> raw = hybridRetrievalService.retrieve(retrievalQuery, RAG_FETCH_POOL, RAG_SIMILARITY_THRESHOLD);
        return mergeCuratedWithSrs(raw, RAG_CURATED_CAP, RAG_TOP_K);
    }

    private String generateRagAnswer(List<Document> found, String customerQuestion, List<Message> historyBeforeCurrent) {
        String userPayload = promptBuilderService.buildRagPayload(found, customerQuestion);
        try {
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(SYSTEM_RAG));
            messages.addAll(historyBeforeCurrent);
            messages.add(new UserMessage(userPayload));
            ChatResponse response = chatModel.call(new Prompt(messages));
            return extractAssistantText(response);
        } catch (Exception e) {
            System.err.println("Chat model error: " + e.getMessage());
            return "Sorry, the AI service is temporarily unavailable. Please try again later.";
        }
    }

    /**
     * True when the model followed the RAG system prompt and declined because excerpts were insufficient
     * (same situation we want in {@code unknown_queries} as when retrieval returned nothing).
     */
    static boolean isRagInsufficientReply(String reply) {
        if (reply == null || reply.isBlank()) {
            return false;
        }
        String t = reply.trim();
        return t.contains("have enough information to answer that right now");
    }

    private void logUnknownQuery(String question, String conversationId) {
        try {
            UnknownQuery row = new UnknownQuery();
            row.setQuestion(question);
            row.setCreatedAt(new Date());
            if (conversationId != null && !conversationId.isBlank()) {
                row.setConversationId(conversationId);
            }
            unknownQueryRepository.save(row);
        } catch (Exception e) {
            System.err.println("Failed to save unknown query: " + e.getMessage());
        }
    }

    /**
     * For short follow-ups, combine with the most recent prior user message in history so vector search is not only "yes".
     */
    static String buildRetrievalQuery(List<Message> history, String latestUser) {
        if (latestUser == null) {
            return "";
        }
        String u = latestUser.trim();
        if (u.isEmpty()) {
            return u;
        }
        if (u.length() >= RETRIEVAL_QUERY_COMBINE_MAX_LEN) {
            return u;
        }
        String prev = lastUserContentInHistory(history);
        if (prev != null && !prev.isBlank()) {
            return (prev + " " + u).trim();
        }
        return u;
    }

    private static String lastUserContentInHistory(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            if (m instanceof UserMessage) {
                return ((UserMessage) m).getText();
            }
        }
        return null;
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
