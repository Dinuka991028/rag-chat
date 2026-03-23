package ai_chat.training;

import ai_chat.domain.UnknownQuery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Normalizes and groups unknown questions for training draft generation.
 */
public final class UnknownQueryNormalizer {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TRAILING_PUNCT = Pattern.compile("[\\p{Punct}\\s]+$");

    private UnknownQueryNormalizer() {
    }

    /**
     * Lowercase + trim + collapse whitespace + remove trailing punctuation noise.
     */
    public static String normalizeQuestion(String rawQuestion) {
        if (rawQuestion == null) {
            return "";
        }
        String q = rawQuestion.toLowerCase(Locale.ROOT).trim();
        q = WHITESPACE.matcher(q).replaceAll(" ");
        q = TRAILING_PUNCT.matcher(q).replaceAll("");
        return q.trim();
    }

    /**
     * Group unknown questions by normalized text, sorted by frequency desc then key asc.
     */
    public static List<GroupedUnknownQuestion> groupByNormalizedQuestion(List<UnknownQuery> rows) {
        Map<String, GroupedUnknownQuestion> grouped = new LinkedHashMap<>();
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        for (UnknownQuery row : rows) {
            if (row == null || row.getQuestion() == null || row.getQuestion().isBlank()) {
                continue;
            }
            String normalized = normalizeQuestion(row.getQuestion());
            if (normalized.isBlank()) {
                continue;
            }

            GroupedUnknownQuestion existing = grouped.get(normalized);
            if (existing == null) {
                grouped.put(normalized, new GroupedUnknownQuestion(row.getQuestion().trim(), normalized, 1));
            } else {
                grouped.put(normalized, new GroupedUnknownQuestion(existing.question(), normalized, existing.count() + 1));
            }
        }

        List<GroupedUnknownQuestion> result = new ArrayList<>(grouped.values());
        result.sort(Comparator
                .comparingInt(GroupedUnknownQuestion::count).reversed()
                .thenComparing(GroupedUnknownQuestion::normalizedQuestion));
        return result;
    }

    /**
     * Return only normalized groups meeting minimum frequency threshold.
     */
    public static List<GroupedUnknownQuestion> eligibleGroups(List<UnknownQuery> rows, int minFrequency) {
        int threshold = Math.max(1, minFrequency);
        return groupByNormalizedQuestion(rows).stream()
                .filter(g -> g.count() >= threshold)
                .toList();
    }

    public record GroupedUnknownQuestion(String question, String normalizedQuestion, int count) {
    }
}
