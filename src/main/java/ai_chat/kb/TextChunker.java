package ai_chat.kb;

import java.util.ArrayList;
import java.util.List;

/** Splits long text into overlapping chunks for embedding (simple character-window strategy). */
public final class TextChunker {

    private TextChunker() {}

    public static List<String> chunk(String text, int maxChars, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.replaceAll("\r\n", "\n").trim();
        if (normalized.length() <= maxChars) {
            return List.of(normalized);
        }
        List<String> out = new ArrayList<>();
        int start = 0;
        int step = Math.max(1, maxChars - overlap);
        while (start < normalized.length()) {
            int end = Math.min(start + maxChars, normalized.length());
            String piece = normalized.substring(start, end).trim();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
            if (end >= normalized.length()) {
                break;
            }
            start += step;
        }
        return out;
    }
}
