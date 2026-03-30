package ai_chat.service;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Builds structured prompt payloads to improve grounding and reduce hallucinations.
 */
@Service
public class PromptBuilderService {

    private static final String RAG_INSTRUCTIONS =
            "- Answer ONLY using [CONTEXT].\n"
                    + "- If context is insufficient or unclear, reply exactly: Sorry, I don't have enough information to answer that right now.\n"
                    + "- Do NOT use outside knowledge.\n"
                    + "- Write in clear, customer-friendly language.\n"
                    + "- Do not mention [CONTEXT], sources, metadata, or how you generated the answer.\n"
                    + "- Start directly with the answer (no lead-ins like \"Based on the provided context\").\n"
                    + "- Keep the answer concise (2-5 sentences).";

    public String buildRagPayload(List<Document> found, String customerQuestion) {
        StringBuilder context = new StringBuilder();
        for (Document d : found) {
            String text = d.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            context.append("- ");
            context.append(text.trim()).append("\n\n");
        }

        return "[CONTEXT]\n"
                + context
                + "\n[QUESTION]\n"
                + (customerQuestion == null ? "" : customerQuestion.trim())
                + "\n\n[INSTRUCTIONS]\n"
                + RAG_INSTRUCTIONS;
    }
}
