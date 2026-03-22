package ai_chat.kb;

import java.util.ArrayList;
import java.util.List;

/**
 * Paragraph-oriented chunking with a running SRS section title. Uses {@link SrsEmbeddingFormat} for each chunk.
 */
public final class SrsChunker {

    private SrsChunker() {}

    public record SrsIndexedChunk(int chunkIndex, String sectionHeading, String textForEmbedding) {}

    public static List<SrsIndexedChunk> chunk(String cleanedText, int maxChars, int overlap) {
        if (cleanedText == null || cleanedText.isBlank()) {
            return List.of();
        }
        List<String> paragraphs = splitParagraphs(cleanedText);
        if (paragraphs.isEmpty()) {
            return List.of();
        }

        String section = "";
        List<String> buffer = new ArrayList<>();
        List<SrsIndexedChunk> chunks = new ArrayList<>();

        for (String para : paragraphs) {
            if (SrsHeadingDetector.isLikelySectionHeading(para)) {
                emitBuffer(chunks, section, buffer, maxChars, overlap);
                buffer.clear();
                section = SrsHeadingDetector.shortenHeading(para);
                continue;
            }
            if (para.length() > maxChars) {
                emitBuffer(chunks, section, buffer, maxChars, overlap);
                buffer.clear();
                for (String part : SrsTextSplit.splitLong(para, maxChars, overlap)) {
                    chunks.add(new SrsIndexedChunk(0, section, SrsEmbeddingFormat.formatChunk(section, part)));
                }
                continue;
            }
            int bufLen = bufferLength(buffer);
            int projected = bufLen + (buffer.isEmpty() ? 0 : 2) + para.length();
            if (!buffer.isEmpty() && projected > maxChars) {
                emitBuffer(chunks, section, buffer, maxChars, overlap);
                buffer.clear();
            }
            buffer.add(para);
        }
        emitBuffer(chunks, section, buffer, maxChars, overlap);

        List<SrsIndexedChunk> numbered = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            SrsIndexedChunk c = chunks.get(i);
            numbered.add(new SrsIndexedChunk(i, c.sectionHeading(), c.textForEmbedding()));
        }
        return numbered;
    }

    private static List<String> splitParagraphs(String text) {
        String[] blocks = text.split("\n\n+");
        List<String> out = new ArrayList<>();
        for (String b : blocks) {
            String p = b.trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    private static int bufferLength(List<String> buffer) {
        int n = 0;
        for (String s : buffer) {
            n += s.length();
        }
        if (buffer.size() > 1) {
            n += 2 * (buffer.size() - 1);
        }
        return n;
    }

    private static void emitBuffer(
            List<SrsIndexedChunk> chunks, String section, List<String> buffer, int maxChars, int overlap) {
        if (buffer.isEmpty()) {
            return;
        }
        String body = String.join("\n\n", buffer);
        if (body.length() <= maxChars) {
            chunks.add(new SrsIndexedChunk(0, section, SrsEmbeddingFormat.formatChunk(section, body)));
            return;
        }
        for (String part : SrsTextSplit.splitLong(body, maxChars, overlap)) {
            chunks.add(new SrsIndexedChunk(0, section, SrsEmbeddingFormat.formatChunk(section, part)));
        }
    }
}
