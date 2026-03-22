package ai_chat.kb;

import java.util.regex.Pattern;

/**
 * Cleans SRS PDF text before chunking: repeated headers/footers and line-wrap artifacts
 * hurt embedding quality more than most people expect.
 */
public final class SrsTextPreprocessor {

    private static final Pattern PAGE_LINE = Pattern.compile("(?m)^\\s*Page\\s+\\d+\\s+of\\s+\\d+\\s*$");
    private static final Pattern HEADER_MINISTRY = Pattern.compile(
            "(?m)^Small Vessels Registration System, Ministry of Transportation and Telecommunications, Kingdom of Bahrain.*$");
    private static final Pattern HEADER_SRS_DATE = Pattern.compile(
            "(?m)^System Requirements Specification\\s+Date:.*$");
    private static final Pattern MULTI_BLANK = Pattern.compile("\n{3,}");

    private SrsTextPreprocessor() {}

    /** Normalize whitespace, strip recurring page chrome, unwrap PDF line breaks. */
    public static String cleanForChunking(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String t = raw.replace("\r\n", "\n").replace('\r', '\n');
        t = HEADER_MINISTRY.matcher(t).replaceAll("");
        t = HEADER_SRS_DATE.matcher(t).replaceAll("");
        t = PAGE_LINE.matcher(t).replaceAll("");
        t = MULTI_BLANK.matcher(t).replaceAll("\n\n");
        t = unwrapSingleNewlines(t);
        t = t.replaceAll("[ \t]+", " ");
        t = MULTI_BLANK.matcher(t).replaceAll("\n\n");
        return t.trim();
    }

    /**
     * PDF text often has one newline per visual line. Turn single newlines into spaces,
     * keep paragraph breaks (blank line runs).
     */
    private static String unwrapSingleNewlines(String text) {
        String placeholder = "\uE000\uE001";
        String marked = text.replaceAll("\n\n+", placeholder);
        marked = marked.replace('\n', ' ');
        return marked.replace(placeholder, "\n\n");
    }
}
