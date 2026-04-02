package ai_chat.routing;

import org.springframework.stereotype.Service;

@Service
public class OfficerQueryRoutingService {

    private final OfficerTaskStatusReportService officerTaskStatusReportService;
    private final OfficerCaseLookupService officerCaseLookupService;

    public OfficerQueryRoutingService(
            OfficerTaskStatusReportService officerTaskStatusReportService,
            OfficerCaseLookupService officerCaseLookupService) {
        this.officerTaskStatusReportService = officerTaskStatusReportService;
        this.officerCaseLookupService = officerCaseLookupService;
    }

    /**
     * Attempts to answer deterministically (no LLM) for common officer-management questions.
     *
     * @param question Safe inbound user message.
     * @param effectiveRole normalized role (`officer` or `customer`).
     * @return deterministic reply when routed; otherwise null to trigger AI fallback.
     */
    public String tryRouteDeterministicReply(String question, String effectiveRole) {
        if (!"officer".equals(effectiveRole)) {
            return null;
        }

        // 1) Deterministic aggregate/report: officer task status counts.
        String statusReport = officerTaskStatusReportService.tryBuildOfficerStatusReport(question);
        if (statusReport != null) {
            return statusReport;
        }

        // 2) Deterministic lookup: ship / job / application-task details.
        return officerCaseLookupService.tryBuildOfficerCaseLookupReply(question);
    }
}

