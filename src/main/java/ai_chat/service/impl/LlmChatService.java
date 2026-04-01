package ai_chat.service.impl;

import ai_chat.domain.UnknownQuery;
import ai_chat.dto.ChatConversationResponse;
import ai_chat.repository.KnowledgeDocumentRepository;
import ai_chat.repository.UnknownQueryRepository;
import ai_chat.service.ChatService;
import ai_chat.service.ConversationHistoryService;
import ai_chat.service.HybridRetrievalService;
import ai_chat.service.PromptBuilderService;
import ai_chat.service.RerankingService;
import ai_chat.service.SecurityGovernanceService;
import org.springframework.ai.chat.messages.AssistantMessage;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern SHIP_NUMBER_PATTERN = Pattern.compile("\\b[A-Z]-\\d{3,}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern JOB_ID_PATTERN = Pattern.compile("\\b\\d{6,}\\b");
    private static final Pattern GREETING_ONLY_PATTERN =
            Pattern.compile("^(hi|hello|hey|salam|salaam|good\\s*(morning|afternoon|evening))\\s*[!.?]*$",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern SENSITIVE_LABELED_VALUE_PATTERN =
            Pattern.compile(
                    "(?im)\\b(customer\\s*id|customer\\s*name|full\\s*name|name|email|e-?mail|phone|mobile|contact\\s*number|national\\s*id|id\\s*number|passport\\s*number|ship\\s*number|job\\s*id|application\\s*id|address|home\\s*address|mailing\\s*address)\\b\\s*[:#-]\\s*([^\\n\\r]+)");
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("\\b(?:\\+?\\d[\\d\\s-]{7,}\\d)\\b");
    private static final Pattern ADDRESS_LINE_PATTERN =
            Pattern.compile("(?im)\\b(address|location|residence)\\b\\s*[:#-]\\s*([^\\n\\r]+)");
    private static final Pattern FULL_NAME_LINE_PATTERN =
            Pattern.compile("(?im)\\b(customer\\s*name|full\\s*name|applicant\\s*name|owner\\s*name|name)\\b\\s*[:#-]\\s*([^\\n\\r]+)");

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

    private static final String SYSTEM_RAG_OFFICER =
            "You are an internal AI assistant for SSRP registry officers in Bahrain.\n"
                    + "Use ONLY the knowledge base excerpts in the user message. Do not use outside knowledge.\n"
                    + "Reply in concise internal-operations style with practical next steps when directly supported by excerpts.\n"
                    + "If the excerpts do not clearly and directly answer the question, respond ONLY with exactly: "
                    + "Sorry, I don't have enough information to answer that right now.\n"
                    + "Do not invent section numbers, system statuses, process stages, or approval outcomes not present in excerpts.";

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

    @Autowired
    private RerankingService rerankingService;

    @Autowired
    private SecurityGovernanceService securityGovernanceService;

    @Override
    public String askAI(String prompt) {
        SecurityGovernanceService.GovernanceDecision decision = securityGovernanceService.checkInboundRequest(prompt);
        if (!decision.allowed()) {
            return decision.userMessage();
        }
        try {
            ChatResponse response = chatModel.call(
                    new Prompt(new SystemMessage(SYSTEM_PLAIN), new UserMessage(decision.safeInput())));
            return applyOutboundGovernance(decision.safeInput(), extractAssistantText(response));
        } catch (Exception e) {
            System.err.println("Chat model error: " + e.getMessage());
            return "Sorry, the AI service is temporarily unavailable. Please try again later.";
        }
    }

    @Override
    public String askAIWithContext(String prompt) {
        return askAIWithContext(prompt, null);
    }

    @Override
    public String askAIWithContext(String prompt, String role) {
        SecurityGovernanceService.GovernanceDecision decision = securityGovernanceService.checkInboundRequest(prompt);
        if (!decision.allowed()) {
            return decision.userMessage();
        }
        String safePrompt = decision.safeInput();
        if (isGreetingOnly(safePrompt)) {
            return "Hello. How can I help you with SSRP today?";
        }
        String effectiveRole = normalizeRole(role);
        if (knowledgeDocumentRepository.count() == 0) {
            return "Knowledge base is empty.";
        }
        List<Document> found = retrieveMerged(safePrompt, effectiveRole);
        if (found.isEmpty()) {
            logUnknownQuery(safePrompt, null, effectiveRole);
            return "Sorry, I don’t have enough information to answer that right now. Please contact support or try another question.";
        }
        String reply = generateRagAnswer(found, safePrompt, List.of(), effectiveRole);
        reply = applyOutboundGovernance(safePrompt, reply);
        if (isRagInsufficientReply(reply)) {
            logUnknownQuery(safePrompt, null, effectiveRole);
        }
        return reply;
    }

    @Override
    public ChatConversationResponse askAIWithHistory(String conversationId, String message) {
        String id = conversationHistoryService.resolveOrCreateConversationId(conversationId);
        List<Message> history = conversationHistoryService.snapshot(id);
        SecurityGovernanceService.GovernanceDecision decision = securityGovernanceService.checkInboundRequest(message);
        if (!decision.allowed()) {
            conversationHistoryService.append(id, message, decision.userMessage());
            return new ChatConversationResponse(id, decision.userMessage());
        }
        String safeMessage = decision.safeInput();
        String reply;
        try {
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(SYSTEM_PLAIN));
            messages.addAll(history);
            messages.add(new UserMessage(safeMessage));
            ChatResponse response = chatModel.call(new Prompt(messages));
            reply = extractAssistantText(response);
            reply = applyOutboundGovernance(safeMessage, reply);
        } catch (Exception e) {
            System.err.println("Chat model error: " + e.getMessage());
            reply = "Sorry, the AI service is temporarily unavailable. Please try again later.";
        }
        conversationHistoryService.append(id, safeMessage, reply);
        return new ChatConversationResponse(id, reply);
    }

    @Override
    public ChatConversationResponse askAIWithContextAndHistory(String conversationId, String message) {
        return askAIWithContextAndHistory(conversationId, message, null);
    }

    @Override
    public ChatConversationResponse askAIWithContextAndHistory(String conversationId, String message, String role) {
        String id = conversationHistoryService.resolveOrCreateConversationId(conversationId);
        List<Message> history = conversationHistoryService.snapshot(id);
        SecurityGovernanceService.GovernanceDecision decision = securityGovernanceService.checkInboundRequest(message);
        if (!decision.allowed()) {
            conversationHistoryService.append(id, message, decision.userMessage());
            return new ChatConversationResponse(id, decision.userMessage());
        }
        String safeMessage = decision.safeInput();
        if (isGreetingOnly(safeMessage)) {
            String reply = "Hello. How can I help you with SSRP today?";
            conversationHistoryService.append(id, safeMessage, reply);
            return new ChatConversationResponse(id, reply);
        }
        String effectiveRole = normalizeRole(role);

        if (knowledgeDocumentRepository.count() == 0) {
            String reply = "Knowledge base is empty.";
            conversationHistoryService.append(id, safeMessage, reply);
            return new ChatConversationResponse(id, reply);
        }

        String retrievalQuery = buildRetrievalQuery(history, safeMessage);
        List<Document> found = retrieveMerged(retrievalQuery, effectiveRole);
        String reply;
        if (found.isEmpty()) {
            logUnknownQuery(safeMessage, id, effectiveRole);
            reply =
                    "Sorry, I don’t have enough information to answer that right now. Please contact support or try another question.";
        } else {
            reply = generateRagAnswer(found, safeMessage, history, effectiveRole);
            reply = applyOutboundGovernance(safeMessage, reply);
            if (isRagInsufficientReply(reply)) {
                logUnknownQuery(safeMessage, id, effectiveRole);
            }
        }
        conversationHistoryService.append(id, safeMessage, reply);
        return new ChatConversationResponse(id, reply);
    }

    private List<Document> retrieveMerged(String retrievalQuery, String role) {
        List<Document> raw = hybridRetrievalService.retrieve(retrievalQuery, RAG_FETCH_POOL, RAG_SIMILARITY_THRESHOLD, role);
        List<Document> scoped = filterByRole(raw, role);
        if (scoped.isEmpty()) {
            return List.of();
        }
        if ("officer".equals(normalizeRole(role))) {
            List<Document> officerFastPath = officerEntityIntentFastPath(scoped, retrievalQuery);
            if (!officerFastPath.isEmpty()) {
                return cap(officerFastPath, RAG_TOP_K);
            }
        }
        List<Document> reranked = rerankingService.rerank(retrievalQuery, scoped);
        return mergeCuratedWithSrs(reranked, RAG_CURATED_CAP, RAG_TOP_K);
    }

    private String generateRagAnswer(List<Document> found, String customerQuestion, List<Message> historyBeforeCurrent, String role) {
        String userPayload = promptBuilderService.buildRagPayload(found, customerQuestion, role);
        String effectiveRole = normalizeRole(role);
        if ("officer".equals(effectiveRole)) {
            userPayload = sanitizeOfficerTextForLlm(userPayload);
        }
        String systemPrompt = "officer".equals(effectiveRole) ? SYSTEM_RAG_OFFICER : SYSTEM_RAG;
        try {
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemPrompt));
            if ("officer".equals(effectiveRole)) {
                messages.addAll(sanitizeOfficerHistoryForLlm(historyBeforeCurrent));
            } else {
                messages.addAll(historyBeforeCurrent);
            }
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

    private void logUnknownQuery(String question, String conversationId, String role) {
        try {
            UnknownQuery row = new UnknownQuery();
            row.setQuestion(question);
            row.setCreatedAt(new Date());
            row.setRole(normalizeRole(role));
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
        // Keep pure greetings standalone; do not leak prior topic into retrieval.
        if (isGreetingOnly(u)) {
            return u;
        }
        String prev = lastUserContentInHistory(history);
        if (prev != null && !prev.isBlank()) {
            return (prev + " " + u).trim();
        }
        return u;
    }

    static boolean isGreetingOnly(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return false;
        }
        return GREETING_ONLY_PATTERN.matcher(t).matches();
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

    private String applyOutboundGovernance(String originalInput, String rawReply) {
        SecurityGovernanceService.GovernanceDecision decision =
                securityGovernanceService.checkOutboundResponse(originalInput, rawReply);
        return decision.allowed() ? decision.safeInput() : decision.userMessage();
    }

    /** Curated FAQ chunks use category != SRS; prefer them when present in the similarity pool. */
    private static boolean isCuratedFaqChunk(Document d) {
        Map<String, Object> meta = d.getMetadata();
        if (meta == null) {
            return false;
        }
        Object c = meta.get("category");
        if (c == null) {
            return false;
        }
        String category = String.valueOf(c);
        return !"SRS".equals(category) && !"OfficerJobSummary".equals(category);
    }

    private static List<Document> filterByRole(List<Document> docs, String role) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        String effectiveRole = normalizeRole(role);
        List<Document> out = new ArrayList<>(docs.size());
        for (Document d : docs) {
            if (matchesRole(d, effectiveRole)) {
                out.add(d);
            }
        }
        return out;
    }

    private static boolean matchesRole(Document d, String role) {
        Map<String, Object> meta = d.getMetadata();
        String source = meta == null || meta.get("source") == null ? "" : String.valueOf(meta.get("source"));
        String category = meta == null || meta.get("category") == null ? "" : String.valueOf(meta.get("category"));
        String audienceRole = meta == null || meta.get("audienceRole") == null ? "" : String.valueOf(meta.get("audienceRole"));

        boolean officerDoc = "officer".equalsIgnoreCase(audienceRole)
                || source.startsWith("officer-")
                || "OfficerJobSummary".equalsIgnoreCase(category);
        if ("officer".equals(role)) {
            return officerDoc;
        }
        return !officerDoc;
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "customer";
        }
        String r = role.trim().toLowerCase();
        return "officer".equals(r) ? "officer" : "customer";
    }

    /**
     * Officer-only deterministic retrieval boost for job/case lookups like:
     * "J-10004 what are pending request".
     * Keeps current rank order from hybrid retrieval and avoids reranker misses.
     */
    private static List<Document> officerEntityIntentFastPath(List<Document> docs, String query) {
        if (docs == null || docs.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        String jobId = extractJobId(query);
        if (jobId != null && !jobId.isBlank()) {
            List<Document> byJob = filterByContains(docs, "Job ID: " + jobId);
            if (byJob.isEmpty()) {
                byJob = filterByContains(docs, jobId);
            }
            if (!byJob.isEmpty()) {
                return byJob;
            }
        }
        String shipNumber = extractShipNumber(query);
        if (shipNumber == null || shipNumber.isBlank()) {
            return List.of();
        }
        List<Document> byShip = filterByContains(docs, "Ship Number: " + shipNumber);
        if (byShip.isEmpty()) {
            byShip = filterByContains(docs, shipNumber);
        }
        if (byShip.isEmpty()) {
            return List.of();
        }
        String statusIntent = extractStatusIntent(query);
        if (statusIntent == null) {
            return byShip;
        }
        List<Document> byStatus = filterByContains(byShip, "Task Status: " + statusIntent);
        if (!byStatus.isEmpty()) {
            return byStatus;
        }
        // If intent exists but no exact-status match was found, keep ship-only matches as fallback.
        return byShip;
    }

    private static String extractShipNumber(String query) {
        Matcher m = SHIP_NUMBER_PATTERN.matcher(query);
        if (!m.find()) {
            return null;
        }
        return m.group().toUpperCase(Locale.ROOT);
    }

    private static String extractJobId(String query) {
        Matcher m = JOB_ID_PATTERN.matcher(query);
        if (!m.find()) {
            return null;
        }
        return m.group();
    }

    private static String extractStatusIntent(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        if (q.contains("pending")) {
            return "PENDING";
        }
        if (q.contains("completed") || q.contains("complete")) {
            return "COMPLETED";
        }
        if (q.contains("cancel") || q.contains("canceled") || q.contains("cancelled")) {
            return "CANCELED";
        }
        return null;
    }

    private static List<Document> filterByContains(List<Document> docs, String needle) {
        String n = needle.toLowerCase(Locale.ROOT);
        List<Document> out = new ArrayList<>();
        for (Document d : docs) {
            String text = d.getText();
            if (text != null && text.toLowerCase(Locale.ROOT).contains(n)) {
                out.add(d);
            }
        }
        return out;
    }

    private static List<Document> cap(List<Document> docs, int max) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        if (docs.size() <= max) {
            return docs;
        }
        return docs.subList(0, max);
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

    /**
     * Officer prompts can include internal records; remove direct PII before LLM handoff.
     * Uses deterministic pseudonyms so references remain consistent inside one prompt.
     */
    private static String sanitizeOfficerTextForLlm(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String masked = text;
        Matcher labeled = SENSITIVE_LABELED_VALUE_PATTERN.matcher(masked);
        StringBuffer sb = new StringBuffer();
        while (labeled.find()) {
            String label = labeled.group(1);
            String value = labeled.group(2) == null ? "" : labeled.group(2).trim();
            String replacement = label + ": " + pseudoMask(label, value);
            labeled.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        labeled.appendTail(sb);
        masked = sb.toString();

        masked = EMAIL_PATTERN.matcher(masked).replaceAll("[MASKED:EMAIL]");
        masked = PHONE_PATTERN.matcher(masked).replaceAll("[MASKED:PHONE]");
        masked = SHIP_NUMBER_PATTERN.matcher(masked).replaceAll("[MASKED:SHIP_NUMBER]");
        masked = JOB_ID_PATTERN.matcher(masked).replaceAll("[MASKED:JOB_ID]");
        masked = maskLineValue(masked, ADDRESS_LINE_PATTERN, "ADDRESS");
        masked = maskLineValue(masked, FULL_NAME_LINE_PATTERN, "NAME");
        return masked;
    }

    private static List<Message> sanitizeOfficerHistoryForLlm(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<Message> sanitized = new ArrayList<>(history.size());
        for (Message message : history) {
            if (message instanceof UserMessage userMessage) {
                sanitized.add(new UserMessage(sanitizeOfficerTextForLlm(userMessage.getText())));
            } else if (message instanceof AssistantMessage assistantMessage) {
                sanitized.add(new AssistantMessage(sanitizeOfficerTextForLlm(assistantMessage.getText())));
            } else {
                sanitized.add(message);
            }
        }
        return sanitized;
    }

    private static String pseudoMask(String label, String value) {
        if (value == null || value.isBlank()) {
            return "[MASKED]";
        }
        String normalizedLabel = label == null ? "field" : label.replaceAll("\\s+", "_").toUpperCase(Locale.ROOT);
        int marker = Math.abs(Objects.hash(normalizedLabel, value.toLowerCase(Locale.ROOT)));
        String token = Integer.toHexString(marker);
        if (token.length() > 8) {
            token = token.substring(0, 8);
        }
        return "[MASKED:" + normalizedLabel + "_" + token + "]";
    }

    private static String maskLineValue(String text, Pattern pattern, String tokenLabel) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String label = matcher.group(1);
            String replacement = label + ": [MASKED:" + tokenLabel + "]";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
