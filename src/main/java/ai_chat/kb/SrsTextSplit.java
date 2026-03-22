package ai_chat.kb;

import java.util.ArrayList;
import java.util.List;

final class SrsTextSplit {

    private SrsTextSplit() {}

    static List<String> splitLong(String para, int maxChars, int overlap) {
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
