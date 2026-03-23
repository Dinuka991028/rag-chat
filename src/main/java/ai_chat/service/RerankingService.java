package ai_chat.service;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM-based reranking layer for retrieved documents.
 */
@Service
public class RerankingService {

    private static final String RERANK_SYSTEM =
            "You are a reranking engine.\n"
                    + "Given a question and passages, return ONLY scoring lines in format: ID<TAB>SCORE\n"
                    + "SCORE must be integer 0-100 where 100=most relevant.\n"
                    + "Do not add explanations, JSON, markdown, headings, or extra text.";

    private final ChatModel chatModel;
    private final boolean rerankEnabled;
    private final int rerankTopK;

    public RerankingService(
            ChatModel chatModel,
            @Value("${conf.rag.rerank.enabled:true}") boolean rerankEnabled,
            @Value("${conf.rag.rerank.top-k:8}") int rerankTopK) {
        this.chatModel = chatModel;
        this.rerankEnabled = rerankEnabled;
        this.rerankTopK = Math.max(1, rerankTopK);
    }

    public List<Document> rerank(String query, List<Document> candidates) {
        if (!rerankEnabled || candidates == null || candidates.isEmpty() || query == null || query.isBlank()) {
            return candidates == null ? List.of() : cap(candidates, rerankTopK);
        }
        List<Passage> passages = toPassages(candidates);
        String payload = buildPayload(query, passages);
        try {
            ChatResponse response = chatModel.call(new Prompt(
                    new SystemMessage(RERANK_SYSTEM),
                    new UserMessage(payload)));
            String text = extractAssistantText(response);
            Map<String, Integer> scores = parseScores(text);
            if (scores.isEmpty()) {
                return cap(candidates, rerankTopK);
            }
            return passages.stream()
                    .sorted(Comparator.comparingInt((Passage p) -> scores.getOrDefault(p.id, 0)).reversed())
                    .limit(rerankTopK)
                    .map(p -> p.document)
                    .toList();
        } catch (Exception e) {
            return cap(candidates, rerankTopK);
        }
    }

    private static List<Passage> toPassages(List<Document> docs) {
        List<Passage> out = new ArrayList<>(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            String id = (d.getId() != null && !d.getId().isBlank())
                    ? d.getId()
                    : "P" + i;
            out.add(new Passage(id, d));
        }
        return out;
    }

    private static String buildPayload(String query, List<Passage> passages) {
        StringBuilder sb = new StringBuilder();
        sb.append("QUESTION:\n").append(query.trim()).append("\n\nPASSAGES:\n");
        for (Passage p : passages) {
            String text = p.document.getText() == null ? "" : p.document.getText().trim();
            if (text.length() > 1200) {
                text = text.substring(0, 1200);
            }
            sb.append(p.id).append('\t').append(text).append("\n");
        }
        sb.append("\nReturn only ID<TAB>SCORE lines.");
        return sb.toString();
    }

    private static Map<String, Integer> parseScores(String raw) {
        Map<String, Integer> out = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String[] lines = raw.split("\\R+");
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            String[] parts = t.split("\\t");
            if (parts.length != 2) {
                continue;
            }
            String id = parts[0].trim();
            String scoreRaw = parts[1].trim().replaceAll("[^0-9-]", "");
            if (id.isEmpty() || scoreRaw.isEmpty()) {
                continue;
            }
            try {
                int score = Integer.parseInt(scoreRaw);
                score = Math.max(0, Math.min(100, score));
                out.put(id, score);
            } catch (NumberFormatException ignored) {
                // skip malformed line
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

    private static String extractAssistantText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String t = response.getResult().getOutput().getText();
        return t == null ? "" : t.trim();
    }

    private record Passage(String id, Document document) {
    }
}
