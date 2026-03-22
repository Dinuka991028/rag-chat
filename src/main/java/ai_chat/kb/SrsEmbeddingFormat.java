package ai_chat.kb;

/**
 * Fixed template for SRS chunks so embeddings and RAG answers share stable semantics
 * (document identity + section + body).
 */
public final class SrsEmbeddingFormat {

    private static final String HEADER =
            "Source: SRS — Bahrain MTT Small Vessel Registration (authoritative specification)\n";

    private SrsEmbeddingFormat() {}

    public static String formatChunk(String sectionHeading, String body) {
        StringBuilder sb = new StringBuilder(HEADER.length() + body.length() + 64);
        sb.append(HEADER);
        if (sectionHeading != null && !sectionHeading.isBlank()) {
            sb.append("Section: ").append(sectionHeading.trim()).append("\n\n");
        }
        sb.append("Content:\n").append(body);
        return sb.toString();
    }
}
