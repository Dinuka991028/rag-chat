package ai_chat.kb;

import java.util.regex.Pattern;

/** Shared SRS heading heuristics (PDF extraction + markdown export + chunking). */
public final class SrsHeadingDetector {

    private static final Pattern SECTION_NUMERIC = Pattern.compile(
            "^\\s*(\\d+(?:\\.\\d+)*)\\s+([A-Za-z0-9][^\\n]{0,300})$");
    private static final Pattern SECTION_INTRO = Pattern.compile(
            "^\\s*(\\d+)\\s+([A-Z][A-Z0-9 ,\\-]{3,80})\\s*$");
    private static final Pattern SECTION_TITLE_CASE = Pattern.compile(
            "^\\s*(\\d+(?:\\.\\d+)*)\\s+([A-Z][a-zA-Z0-9 ,\\-]{3,120})$");

    private SrsHeadingDetector() {}

    public static boolean isLikelySectionHeading(String para) {
        if (para == null || para.startsWith("[DOCUMENT:")) {
            return false;
        }
        if (para.length() > 220) {
            return false;
        }
        if (SECTION_NUMERIC.matcher(para).matches()
                || SECTION_INTRO.matcher(para).matches()
                || SECTION_TITLE_CASE.matcher(para).matches()) {
            return true;
        }
        return para.length() < 100 && para.equals(para.toUpperCase(java.util.Locale.ROOT))
                && para.chars().filter(Character::isLetter).count() > 3;
    }

    public static String shortenHeading(String para) {
        String oneLine = para.replace('\n', ' ').trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 197) + "..." : oneLine;
    }
}
