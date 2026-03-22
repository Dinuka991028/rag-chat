package ai_chat.kb;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Paragraph-oriented chunking with a running SRS section title. Each chunk's text begins with
 * {@code Section: ...} when known, so embeddings align better with user questions.
 */
public final class SrsChunker {

    private static final Pattern SECTION_NUMERIC = Pattern.compile(
            "^\\s*(\\d+(?:\\.\\d+)*)\\s+([A-Za-z0-9][^\\n]{0,300})$");
    private static final Pattern SECTION_INTRO = Pattern.compile(
            "^\\s*(\\d+)\\s+([A-Z][A-Z0-9 ,\\-]{3,80})\\s*$");

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
            if (isLikelySectionHeading(para)) {
                emitBuffer(chunks, section, buffer, maxChars, overlap);
                buffer.clear();
                section = shortenHeading(para);
                continue;
            }
            if (para.length() > maxChars) {
                emitBuffer(chunks, section, buffer, maxChars, overlap);
                buffer.clear();
                for (String part : splitLong(para, maxChars, overlap)) {
                    chunks.add(new SrsIndexedChunk(0, section, embedText(section, part)));
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
            chunks.add(new SrsIndexedChunk(0, section, embedText(section, body)));
            return;
        }
        for (String part : splitLong(body, maxChars, overlap)) {
            chunks.add(new SrsIndexedChunk(0, section, embedText(section, part)));
        }
    }

    private static String embedText(String section, String body) {
        if (section == null || section.isBlank()) {
            return body;
        }
        return "Section: " + section.trim() + "\n\n" + body;
    }

    private static boolean isLikelySectionHeading(String para) {
        if (para.length() > 220) {
            return false;
        }
        if (SECTION_NUMERIC.matcher(para).matches() || SECTION_INTRO.matcher(para).matches()) {
            return true;
        }
        return para.length() < 100 && para.equals(para.toUpperCase(java.util.Locale.ROOT))
                && para.chars().filter(Character::isLetter).count() > 3;
    }

    private static String shortenHeading(String para) {
        String oneLine = para.replace('\n', ' ').trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 197) + "..." : oneLine;
    }

    private static List<String> splitLong(String para, int maxChars, int overlap) {
        List<String> parts = new ArrayList<>();
        int step = Math.max(1, maxChars - overlap);
        int pos = 0;
        while (pos < para.length()) {
            int end = Math.min(pos + maxChars, para.length());
            if (end < para.length()) {
                int breakAt = para.lastIndexOf('.', end - 1);
                if (breakAt > pos + maxChars / 3) {
                    end = breakAt + 1;
                }
            }
            String piece = para.substring(pos, end).trim();
            if (!piece.isEmpty()) {
                parts.add(piece);
            }
            if (end >= para.length()) {
                break;
            }
            pos += step;
        }
        return parts;
    }
}
