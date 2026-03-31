package ai_chat.service;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Builds structured prompt payloads to improve grounding and reduce hallucinations.
 */
@Service
public class PromptBuilderService {

    private static final String RAG_INSTRUCTIONS_CUSTOMER =
            "- Answer ONLY using [CONTEXT].\n"
                    + "- If context is insufficient or unclear, reply exactly: Sorry, I don't have enough information to answer that right now.\n"
                    + "- Do NOT use outside knowledge.\n"
                    + "- Write in clear, customer-friendly language.\n"
                    + "- Do not mention [CONTEXT], sources, metadata, or how you generated the answer.\n"
                    + "- Start directly with the answer (no lead-ins like \"Based on the provided context\").\n"
                    + "- Keep the answer concise (2-5 sentences).";

    private static final String RAG_INSTRUCTIONS_OFFICER =
            "- Answer ONLY using [CONTEXT].\n"
                    + "- If context is insufficient or unclear, reply exactly: Sorry, I don't have enough information to answer that right now.\n"
                    + "- Do NOT use outside knowledge.\n"
                    + "- Write in concise internal-operations style for registry officers.\n"
                    + "- Do not mention [CONTEXT], sources, metadata, or how you generated the answer.\n"
                    + "- Start directly with the answer and use short actionable points when useful.\n"
                    + "- Keep the answer concise (2-6 sentences).";

    public String buildRagPayload(List<Document> found, String customerQuestion, String role) {
        StringBuilder context = new StringBuilder();
        for (Document d : found) {
            String text = d.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            context.append("- ");
            context.append(text.trim()).append("\n\n");
        }

        String effectiveRole = normalizeRole(role);
        String instructions = "officer".equals(effectiveRole) ? RAG_INSTRUCTIONS_OFFICER : RAG_INSTRUCTIONS_CUSTOMER;

        return "[CONTEXT]\n"
                + context
                + "\n[ROLE]\n"
                + effectiveRole
                + "\n[QUESTION]\n"
                + (customerQuestion == null ? "" : customerQuestion.trim())
                + "\n\n[INSTRUCTIONS]\n"
                + instructions;
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "customer";
        }
        String r = role.trim().toLowerCase();
        return "officer".equals(r) ? "officer" : "customer";
    }
}
