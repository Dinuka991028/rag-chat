package ai_chat.service.impl;

import ai_chat.service.SecurityGovernanceService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class RequestSecurityGovernanceService implements SecurityGovernanceService {
    private static final Pattern CUSTOMER_ID_PATTERN = Pattern.compile("\\bcustomer\\s*id\\s*[:#-]?\\s*([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);

    @Value("${conf.security.enabled:true}")
    private boolean enabled;

    @Value("${conf.security.max-input-chars:4000}")
    private int maxInputChars;

    @Value("${conf.security.blocked-patterns:ssn,credit card,password,api key,private key,authorization: bearer}")
    private String blockedPatternsCsv;

    private List<String> blockedPatterns;

    @PostConstruct
    void init() {
        blockedPatterns = Arrays.stream(blockedPatternsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toList());
    }

    @Override
    public GovernanceDecision checkInboundRequest(String rawInput) {
        String normalized = rawInput == null ? "" : rawInput.trim();
        if (normalized.isEmpty()) {
            return GovernanceDecision.block("Your request is empty. Please provide a valid question.");
        }
        if (!enabled) {
            return GovernanceDecision.allow(normalized);
        }
        if (normalized.length() > maxInputChars) {
            return GovernanceDecision.block(
                    "Your request is too long for policy limits. Please shorten it and try again.");
        }

        String lowered = normalized.toLowerCase(Locale.ROOT);
        for (String blockedPattern : blockedPatterns) {
            if (lowered.contains(blockedPattern)) {
                return GovernanceDecision.block(
                        "Your request cannot be processed due to security and governance policy.");
            }
        }
        return GovernanceDecision.allow(normalized);
    }

    @Override
    public GovernanceDecision checkOutboundResponse(String originalInput, String modelOutput) {
        String response = modelOutput == null ? "" : modelOutput.trim();
        if (!enabled) {
            return GovernanceDecision.allow(response);
        }
        if (response.isEmpty()) {
            return GovernanceDecision.allow(response);
        }

        Set<String> inputIds = extractCustomerIds(originalInput);
        if (inputIds.isEmpty()) {
            return GovernanceDecision.allow(response);
        }
        Set<String> outputIds = extractCustomerIds(response);
        if (outputIds.isEmpty()) {
            return GovernanceDecision.allow(response);
        }
        if (!inputIds.equals(outputIds)) {
            return GovernanceDecision.block(
                    "Response blocked by governance policy: customer ID mismatch detected. Please retry with verified internal data.");
        }
        return GovernanceDecision.allow(response);
    }

    private static Set<String> extractCustomerIds(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Matcher matcher = CUSTOMER_ID_PATTERN.matcher(text);
        while (matcher.find()) {
            out.add(matcher.group(1).trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
