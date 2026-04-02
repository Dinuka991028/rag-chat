package ai_chat.routing;

import ai_chat.domain.OfficerKnowledgeDocument;
import ai_chat.repository.OfficerKnowledgeDocumentRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OfficerTaskStatusReportService {

    private static final Pattern TASK_STATUS_FROM_CONTENT =
            Pattern.compile("(?im)^Task\\s+Status\\s*:\\s*(.+)$");

    private final OfficerKnowledgeDocumentRepository officerKnowledgeDocumentRepository;

    public OfficerTaskStatusReportService(OfficerKnowledgeDocumentRepository officerKnowledgeDocumentRepository) {
        this.officerKnowledgeDocumentRepository = officerKnowledgeDocumentRepository;
    }

    /**
     * Deterministic officer-only task status counting.
     *
     * @return report text when the question is clearly a status-count request; otherwise null.
     */
    public String tryBuildOfficerStatusReport(String query) {
        if (!isOfficerStatusCountIntent(query)) {
            return null;
        }
        List<String> requestedStatuses = extractRequestedStatuses(query);
        if (requestedStatuses.isEmpty()) {
            return null;
        }
        Map<String, Integer> counts = countOfficerTaskStatuses();
        if (counts.isEmpty()) {
            return "Sorry, I don't have enough information to answer that right now.";
        }

        List<String> lines = new ArrayList<>();
        lines.add("Officer task status report:");
        int total = 0;
        for (String status : requestedStatuses) {
            int value = counts.getOrDefault(status, 0);
            total += value;
            lines.add("- " + toDisplayStatus(status) + ": " + value);
        }
        if (requestedStatuses.size() > 1) {
            lines.add("- Total (requested statuses): " + total);
        }
        return String.join("\n", lines);
    }

    static boolean isOfficerStatusCountIntent(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String q = query.toLowerCase(Locale.ROOT);

        boolean hasCountWord =
                q.contains("how many")
                        || q.contains("count")
                        || q.contains("report")
                        || q.contains("summary")
                        || q.contains("stats")
                        || q.contains("statistics");
        if (!hasCountWord) {
            return false;
        }

        return q.contains("completed")
                || q.contains("complete")
                || q.contains("cancel")
                || q.contains("canceled")
                || q.contains("cancelled")
                || q.contains("pending")
                || q.contains("in progress");
    }

    static List<String> extractRequestedStatuses(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.toLowerCase(Locale.ROOT);
        List<String> statuses = new ArrayList<>();
        if (q.contains("completed") || q.contains("complete")) {
            statuses.add("COMPLETED");
        }
        if (q.contains("cancel") || q.contains("canceled") || q.contains("cancelled")) {
            statuses.add("CANCELED");
        }
        if (q.contains("pending")) {
            statuses.add("PENDING");
        }
        if (q.contains("in progress")) {
            statuses.add("IN_PROGRESS");
        }
        return statuses;
    }

    private Map<String, Integer> countOfficerTaskStatuses() {
        List<OfficerKnowledgeDocument> rows = officerKnowledgeDocumentRepository.findAll();
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (OfficerKnowledgeDocument row : rows) {
            if (row == null || row.getContent() == null || row.getContent().isBlank()) {
                continue;
            }
            String status = extractTaskStatusFromContent(row.getContent());
            if (status == null || status.isBlank()) {
                continue;
            }
            counts.merge(status, 1, Integer::sum);
        }
        return counts;
    }

    static String extractTaskStatusFromContent(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        Matcher m = TASK_STATUS_FROM_CONTENT.matcher(content);
        if (!m.find()) {
            return null;
        }
        String raw = m.group(1).trim();
        if (raw.isEmpty()) {
            return null;
        }
        String normalized = raw.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if ("CANCELLED".equals(normalized)) {
            return "CANCELED";
        }
        return normalized;
    }

    private static String toDisplayStatus(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return "-";
        }
        String s = normalized.replace('_', ' ').toLowerCase(Locale.ROOT);
        String[] parts = s.split(" ");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}

