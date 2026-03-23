package ai_chat.service;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Builds structured prompt payloads to improve grounding and reduce hallucinations.
 */
@Service
public class PromptBuilderService {

    private static final String RAG_INSTRUCTIONS =
            "- Answer ONLY using [CONTEXT].\n"
                    + "- If context is insufficient or unclear, reply exactly: Sorry, I don't have enough information to answer that right now.\n"
                    + "- Do NOT use outside knowledge.\n"
                    + "- Keep the answer concise and formal.\n"
                    + "- If source labels are present in context, cite them inline when relevant.";

    public String buildRagPayload(List<Document> found, String customerQuestion) {
        StringBuilder context = new StringBuilder();
        for (Document d : found) {
            String text = d.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            context.append("- ");
            appendSourceLabel(context, d.getMetadata());
            context.append(text.trim()).append("\n\n");
        }

        return "[CONTEXT]\n"
                + context
                + "\n[QUESTION]\n"
                + (customerQuestion == null ? "" : customerQuestion.trim())
                + "\n\n[INSTRUCTIONS]\n"
                + RAG_INSTRUCTIONS;
    }

    private static void appendSourceLabel(StringBuilder out, Map<String, Object> metadata) {
        if (metadata == null) {
            return;
        }
        Object source = metadata.get("source");
        Object heading = metadata.get("sectionHeading");
        if (source == null && heading == null) {
            return;
        }
        out.append("[source=");
        out.append(source == null ? "unknown" : String.valueOf(source));
        if (heading != null && !String.valueOf(heading).isBlank()) {
            out.append(", section=").append(String.valueOf(heading));
        }
        out.append("] ");
    }
}
