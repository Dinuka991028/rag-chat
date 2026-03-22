package ai_chat.tools;

import ai_chat.kb.PdfTextExtractor;
import ai_chat.kb.SrsHeadingDetector;
import ai_chat.kb.SrsTextPreprocessor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One-off: convert {@code ssrp-srs.pdf} to LLM-friendly markdown with {@code ##} section headings.
 * <p>
 * Run (after {@code mvn compile}):
 * </p>
 * <pre>
 * mvn -q exec:java -Dexec.mainClass=ai_chat.tools.SrsPdfToMarkdownExporter \
 *   -Dexec.args="src/main/resources/kb/ssrp-srs.pdf src/main/resources/kb/ssrp-srs-llm.md"
 * </pre>
 * Then edit {@code ssrp-srs-llm.md} for clarity; keep the PDF only as the legal/archival source if needed.
 */
public final class SrsPdfToMarkdownExporter {

    private SrsPdfToMarkdownExporter() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: SrsPdfToMarkdownExporter <input.pdf> <output.md>");
            System.exit(1);
        }
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);
        String raw = PdfTextExtractor.extractText(Files.newInputStream(in));
        String cleaned = SrsTextPreprocessor.cleanForChunking(raw);
        String md = toMarkdown(cleaned);
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.writeString(out, md, StandardCharsets.UTF_8);
        System.out.println("Wrote " + out.toAbsolutePath() + " (" + md.length() + " chars)");
    }

    static String toMarkdown(String cleaned) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("format: markdown\n");
        sb.append("source: exported-from-pdf\n");
        sb.append("---\n\n");
        sb.append("# SRS — Small Vessel Registration System (Bahrain MTT)\n\n");
        sb.append(
                "**Maintain this file for RAG.** Edit headings and wording for clarity; the PDF remains the formal document.\n\n");
        for (String para : cleaned.split("\n\n+")) {
            String p = para.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (SrsHeadingDetector.isLikelySectionHeading(p)) {
                sb.append("## ").append(p.replace('\n', ' ').trim()).append("\n\n");
            } else {
                sb.append(p).append("\n\n");
            }
        }
        return sb.toString();
    }
}
