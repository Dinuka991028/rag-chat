package ai_chat.routing;

import ai_chat.domain.OfficerKnowledgeDocument;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OfficerCaseLookupService {

    /**
     * Vessel identifiers: {@code BH-…} for ships, {@code J-…} for jet skis.
     */
    private static final Pattern SHIP_NUMBER_PATTERN =
            Pattern.compile("\\b(?:BH|J)-\\d{3,}\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern JOB_ID_PATTERN = Pattern.compile("\\b\\d{6,}\\b");

    private final MongoTemplate mongoTemplate;

    public OfficerCaseLookupService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Deterministic officer-only lookup.
     *
     * Only triggers when a question contains an explicit identifier (ship number / job id / task id).
     * Returns null when we can't safely extract parameters.
     */
    public String tryBuildOfficerCaseLookupReply(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }

        // 1) Vessel / ship lookup (highest confidence).
        String ship = extractShipNumber(question);
        if (ship != null) {
            // If the ship number is explicitly present, do not reinterpret its digits as task/job ids.
            return tryBuildByShip(ship);
        }

        // 2) "Application" lookup (maps to jobId in this KB).
        String applicationId = extractApplicationId(question).orElse(null);
        if (applicationId != null) {
            return tryBuildByJobId(applicationId);
        }

        // 3) Explicit task id lookup (taskId is a UUID in this KB).
        String explicitTaskId = extractExplicitTaskId(question).orElse(null);
        if (explicitTaskId != null) {
            return tryBuildByTaskId(explicitTaskId);
        }

        // 4) Job id lookup (implicit).
        String jobId = extractJobId(question).orElse(null);
        if (jobId != null) {
            return tryBuildByJobId(jobId);
        }

        return null;
    }

    private String tryBuildByShip(String shipNumberRaw) {
        List<String> candidates = shipCandidates(shipNumberRaw);
        for (String candidate : candidates) {
            List<OfficerKnowledgeDocument> docs = findOfficerDocsByContentRegex(
                    buildShipRegex(candidate),
                    6);
            if (docs.isEmpty()) {
                continue;
            }
            return OfficerCaseSummary.formatShipSummary(candidate, docs);
        }
        return null;
    }

    private String tryBuildByTaskId(String taskId) {
        List<OfficerKnowledgeDocument> docs = findOfficerDocsByContentRegex(
                buildLabelRegex("Task ID", taskId),
                6);
        if (docs.isEmpty()) {
            return null;
        }
        return OfficerCaseSummary.formatTaskSummary(docs);
    }

    private String tryBuildByJobId(String jobId) {
        List<OfficerKnowledgeDocument> docs = findOfficerDocsByContentRegex(
                buildLabelRegex("Job ID", jobId),
                6);
        if (docs.isEmpty()) {
            return null;
        }
        return OfficerCaseSummary.formatJobSummary(jobId, docs);
    }

    private List<OfficerKnowledgeDocument> findOfficerDocsByContentRegex(String contentRegex, int limit) {
        Query q = new Query();
        q.addCriteria(Criteria.where("audienceRole").is("officer"));
        q.addCriteria(Criteria.where("category").is("OfficerJobSummary"));
        q.addCriteria(Criteria.where("content").regex(contentRegex, "i"));
        q.limit(Math.max(1, limit));
        return mongoTemplate.find(q, OfficerKnowledgeDocument.class);
    }

    private String buildShipRegex(String shipNumber) {
        // More precise than matching the raw token; reduces false hits.
        return buildLabelRegex("Ship Number", shipNumber);
    }

    private static String buildLabelRegex(String label, String value) {
        // Match a single label line like: "Ship Number: BH-1245"
        return "(?i)" + Pattern.quote(label) + "\\s*:\\s*" + Pattern.quote(value) + "\\b";
    }

    private Optional<String> extractApplicationId(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }

        // Example: "What is the status of application 24610033?"
        Pattern p =
                Pattern.compile("application\\s*(?:no\\.?|#|number)?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(question);
        if (!m.find()) {
            return Optional.empty();
        }
        String v = m.group(1);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v.trim());
    }

    private static final Pattern UUID_TASK_ID_PATTERN =
            Pattern.compile(
                    "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");

    private Optional<String> extractExplicitTaskId(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }

        // Example: "task id 6f41b9ba-86dc-11ef-a833-2cfda159474f"
        Pattern p =
                Pattern.compile(
                        "task\\s*(?:id)?\\s*(\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b)",
                        Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(question);
        if (m.find()) {
            String v = m.group(1);
            return v == null || v.isBlank() ? Optional.empty() : Optional.of(v.trim());
        }

        // Example: "show task <uuid>"
        String lower = question.toLowerCase(Locale.ROOT);
        if (!lower.contains("task")) {
            return Optional.empty();
        }
        Matcher m2 = UUID_TASK_ID_PATTERN.matcher(question);
        if (!m2.find()) {
            return Optional.empty();
        }
        return Optional.of(m2.group().trim());
    }

    private Optional<String> extractJobId(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        Matcher m = JOB_ID_PATTERN.matcher(question);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(m.group().trim());
    }

    private String extractShipNumber(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        Matcher m = SHIP_NUMBER_PATTERN.matcher(question);
        if (!m.find()) {
            return null;
        }
        return m.group().toUpperCase(Locale.ROOT);
    }

    private List<String> shipCandidates(String shipNumberRaw) {
        String s = shipNumberRaw == null ? "" : shipNumberRaw.trim().toUpperCase(Locale.ROOT);
        if (s.isBlank()) {
            return List.of();
        }
        return List.of(s);
    }

    private static final class OfficerCaseSummary {
        private final String jobId;
        private final String taskName;
        private final String serviceName;
        private final String shipNumber;
        private final String shipName;
        private final String vesselType;
        private final String customerName;
        private final String submitDate;
        private final String completedDate;
        private final String taskStatus;
        private final List<String> taskHistoryLines;

        private OfficerCaseSummary(
                String jobId,
                String taskName,
                String serviceName,
                String shipNumber,
                String shipName,
                String vesselType,
                String customerName,
                String submitDate,
                String completedDate,
                String taskStatus,
                List<String> taskHistoryLines) {
            this.jobId = jobId;
            this.taskName = taskName;
            this.serviceName = serviceName;
            this.shipNumber = shipNumber;
            this.shipName = shipName;
            this.vesselType = vesselType;
            this.customerName = customerName;
            this.submitDate = submitDate;
            this.completedDate = completedDate;
            this.taskStatus = taskStatus;
            this.taskHistoryLines = taskHistoryLines == null ? List.of() : taskHistoryLines;
        }

        static String formatShipSummary(String requestedShipNumber, List<OfficerKnowledgeDocument> docs) {
            List<OfficerCaseSummary> summaries = docs.stream()
                    .map(d -> OfficerCaseSummary.fromContent(d.getContent()))
                    .filter(s -> s != null && (s.jobId != null || s.taskName != null || s.taskStatus != null))
                    .toList();

            if (summaries.isEmpty()) {
                return null;
            }

            Map<String, OfficerCaseSummary> unique = new LinkedHashMap<>();
            for (OfficerCaseSummary s : summaries) {
                String key =
                        emptyToDash(s.jobId)
                                + "|"
                                + emptyToDash(s.taskName)
                                + "|"
                                + emptyToDash(s.submitDate);
                if (!unique.containsKey(key)) {
                    unique.put(key, s);
                }
            }

            List<OfficerCaseSummary> ordered = unique.values().stream()
                    .sorted(Comparator.comparing((OfficerCaseSummary s) -> s.submitDate == null ? "" : s.submitDate).reversed())
                    .toList();

            StringBuilder out = new StringBuilder();
            out.append("Vessel case summary").append('\n');
            out.append("Ship Number: ").append(requestedShipNumber).append('\n');

            OfficerCaseSummary first = ordered.get(0);
            if (first.shipName != null) {
                out.append("Ship Name: ").append(first.shipName).append('\n');
            }
            if (first.vesselType != null) {
                out.append("Vessel Type: ").append(first.vesselType).append('\n');
            }
            if (first.customerName != null) {
                out.append("Customer Name: ").append(first.customerName).append('\n');
            }

            out.append('\n').append("Tasks:").append('\n');
            for (OfficerCaseSummary s : ordered) {
                out.append("- Job ID: ").append(emptyToDash(s.jobId))
                        .append(" | Status: ").append(emptyToDash(s.taskStatus));
                if (s.taskName != null && !s.taskName.isBlank()) {
                    out.append(" | Task: ").append(s.taskName);
                }
                if (s.serviceName != null && !s.serviceName.isBlank()) {
                    out.append(" | Service: ").append(s.serviceName);
                }
                if (s.submitDate != null && !s.submitDate.isBlank()) {
                    out.append(" | Submitted: ").append(s.submitDate);
                }
                out.append('\n');
            }

            // Add the latest task-history line from the most recent submitDate (first in ordered).
            OfficerCaseSummary latest = ordered.get(0);
            if (!latest.taskHistoryLines.isEmpty()) {
                String last = latest.taskHistoryLines.get(latest.taskHistoryLines.size() - 1);
                out.append('\n').append("Latest update: ").append(last);
            }

            return out.toString().trim();
        }

        static String formatTaskSummary(List<OfficerKnowledgeDocument> docs) {
            List<OfficerCaseSummary> summaries = docs.stream()
                    .map(d -> OfficerCaseSummary.fromContent(d.getContent()))
                    .filter(s -> s != null)
                    .toList();
            if (summaries.isEmpty()) {
                return null;
            }
            OfficerCaseSummary s = summaries.get(0);

            StringBuilder out = new StringBuilder();
            out.append("Application/Task status").append('\n');
            out.append("Job ID: ").append(emptyToDash(s.jobId)).append('\n');
            if (s.taskName != null) out.append("Task Name: ").append(s.taskName).append('\n');
            if (s.taskStatus != null) out.append("Task Status: ").append(s.taskStatus).append('\n');
            if (s.serviceName != null) out.append("Service Name: ").append(s.serviceName).append('\n');
            if (s.shipNumber != null) out.append("Ship Number: ").append(s.shipNumber).append('\n');
            if (s.shipName != null) out.append("Ship Name: ").append(s.shipName).append('\n');
            if (s.submitDate != null) out.append("Submit Date: ").append(s.submitDate).append('\n');
            if (s.completedDate != null) out.append("Completed Date: ").append(s.completedDate).append('\n');

            if (s.taskHistoryLines != null && !s.taskHistoryLines.isEmpty()) {
                out.append('\n').append("Task History (latest):").append('\n');
                int start = Math.max(0, s.taskHistoryLines.size() - 3);
                for (int i = start; i < s.taskHistoryLines.size(); i++) {
                    out.append("- ").append(s.taskHistoryLines.get(i)).append('\n');
                }
            }

            return out.toString().trim();
        }

        static String formatJobSummary(String requestedJobId, List<OfficerKnowledgeDocument> docs) {
            List<OfficerCaseSummary> summaries = docs.stream()
                    .map(d -> OfficerCaseSummary.fromContent(d.getContent()))
                    .filter(s -> s != null)
                    .toList();
            if (summaries.isEmpty()) {
                return null;
            }

            OfficerCaseSummary first = summaries.get(0);
            StringBuilder out = new StringBuilder();
            out.append("Job case summary").append('\n');
            out.append("Job ID: ").append(requestedJobId).append('\n');
            if (first.shipNumber != null) out.append("Ship Number: ").append(first.shipNumber).append('\n');
            if (first.shipName != null) out.append("Ship Name: ").append(first.shipName).append('\n');
            if (first.vesselType != null) out.append("Vessel Type: ").append(first.vesselType).append('\n');
            if (first.customerName != null) out.append("Customer Name: ").append(first.customerName).append('\n');

            out.append('\n').append("Tasks:").append('\n');
            for (OfficerCaseSummary s : summaries) {
                out.append("- Status: ").append(emptyToDash(s.taskStatus))
                        .append(" | Task: ").append(emptyToDash(s.taskName))
                        .append('\n');
            }
            return out.toString().trim();
        }

        private static OfficerCaseSummary fromContent(String content) {
            if (content == null || content.isBlank()) {
                return null;
            }

            String jobId = extractLabel(content, "Job ID");
            String taskName = extractLabel(content, "Task Name");
            String serviceName = extractLabel(content, "Service Name");
            String shipNumber = extractLabel(content, "Ship Number");
            String shipName = extractLabel(content, "Ship Name");
            String vesselType = extractLabel(content, "Vessel Type");
            String customerName = extractLabel(content, "Customer Name");
            String submitDate = extractLabel(content, "Submit Date");
            String completedDate = extractLabel(content, "Completed Date");
            String taskStatus = extractLabel(content, "Task Status");
            List<String> taskHistoryLines = extractTaskHistoryLines(content);

            return new OfficerCaseSummary(
                    jobId,
                    taskName,
                    serviceName,
                    shipNumber,
                    shipName,
                    vesselType,
                    customerName,
                    submitDate,
                    completedDate,
                    taskStatus,
                    taskHistoryLines);
        }

        private static List<String> extractTaskHistoryLines(String content) {
            int idx = content.indexOf("Task History:");
            if (idx < 0) {
                return List.of();
            }
            String tail = content.substring(idx);
            String[] lines = tail.split("\\R");
            List<String> out = new ArrayList<>();
            for (String line : lines) {
                if (line == null) {
                    continue;
                }
                String t = line.trim();
                if (t.isBlank()) {
                    continue;
                }
                // Lines look like: "1) Task=...; Status=...; ..."
                if (t.matches("^\\d+\\)\\s*.*")) {
                    out.add(t);
                }
            }
            return out;
        }

        private static String extractLabel(String content, String label) {
            Pattern p =
                    Pattern.compile("(?im)^" + Pattern.quote(label) + "\\s*:\\s*(.+)$");
            Matcher m = p.matcher(content);
            if (!m.find()) {
                return null;
            }
            String v = m.group(1);
            if (v == null) {
                return null;
            }
            String cleaned = v.trim();
            return cleaned.isBlank() ? null : cleaned;
        }

        private static String emptyToDash(String s) {
            return (s == null || s.isBlank()) ? "-" : s;
        }
    }
}

