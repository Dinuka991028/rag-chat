package ai_chat.kb;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Chunks LLM-maintained SRS markdown (headings, lists) — preferred over raw PDF text when available.
 */
public final class SrsMarkdownChunker {

    private static final Pattern YAML_FRONTMATTER = Pattern.compile(
            "^---\\s*\\R[\\s\\S]*?\\R---\\s*\\R", Pattern.MULTILINE);
    private static final Pattern MD_HEADING = Pattern.compile("^#{1,3}\\s+(.+)$");

    private SrsMarkdownChunker() {}

    public static List<SrsChunker.SrsIndexedChunk> chunk(String markdown, int maxChars, int overlap) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        String md = YAML_FRONTMATTER.matcher(markdown).replaceFirst("");
        List<SectionBlock> blocks = parseSections(md);
        List<SrsChunker.SrsIndexedChunk> chunks = new ArrayList<>();
        for (SectionBlock block : blocks) {
            String heading = block.heading();
            String body = block.body().trim();
            if (body.isEmpty()) {
                continue;
            }
            if (body.length() <= maxChars) {
                chunks.add(new SrsChunker.SrsIndexedChunk(0, heading, SrsEmbeddingFormat.formatChunk(heading, body)));
            } else {
                for (String part : SrsTextSplit.splitLong(body, maxChars, overlap)) {
                    chunks.add(new SrsChunker.SrsIndexedChunk(0, heading, SrsEmbeddingFormat.formatChunk(heading, part)));
                }
            }
        }
        List<SrsChunker.SrsIndexedChunk> numbered = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            SrsChunker.SrsIndexedChunk c = chunks.get(i);
            numbered.add(new SrsChunker.SrsIndexedChunk(i, c.sectionHeading(), c.textForEmbedding()));
        }
        return numbered;
    }

    private record SectionBlock(String heading, String body) {}

    private static List<SectionBlock> parseSections(String md) {
        List<SectionBlock> out = new ArrayList<>();
        String[] lines = md.replace("\r\n", "\n").split("\n", -1);
        String currentHeading = null;
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            var m = MD_HEADING.matcher(line);
            if (m.matches()) {
                if (currentHeading != null || body.length() > 0) {
                    String h = currentHeading != null ? currentHeading : "Overview";
                    out.add(new SectionBlock(h, body.toString()));
                }
                currentHeading = m.group(1).trim();
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        if (currentHeading != null || body.length() > 0) {
            String h = currentHeading != null ? currentHeading : "Overview";
            out.add(new SectionBlock(h, body.toString()));
        }
        return out;
    }
}
