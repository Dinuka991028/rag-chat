package ai_chat.service.impl;

import ai_chat.service.OfficerPromptPrivacyService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OfficerPromptPrivacyServiceImpl implements OfficerPromptPrivacyService {

    private static final Pattern SENSITIVE_LABELED_VALUE_PATTERN =
            Pattern.compile(
                    "(?im)\\b(customer\\s*id|customer\\s*name|full\\s*name|name|email|e-?mail|phone|mobile|contact\\s*number|national\\s*id|id\\s*number|passport\\s*number|ship\\s*number|job\\s*id|application\\s*id|address|home\\s*address|mailing\\s*address)\\b\\s*[:#-]\\s*([^\\n\\r]+)");
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("\\b(?:\\+?\\d[\\d\\s-]{7,}\\d)\\b");
    private static final Pattern SHIP_NUMBER_PATTERN = Pattern.compile("\\b[A-Z]-\\d{3,}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern JOB_ID_PATTERN = Pattern.compile("\\b\\d{6,}\\b");
    private static final Pattern ADDRESS_LINE_PATTERN =
            Pattern.compile("(?im)\\b(address|location|residence)\\b\\s*[:#-]\\s*([^\\n\\r]+)");
    private static final Pattern FULL_NAME_LINE_PATTERN =
            Pattern.compile("(?im)\\b(customer\\s*name|full\\s*name|applicant\\s*name|owner\\s*name|name)\\b\\s*[:#-]\\s*([^\\n\\r]+)");
    private static final Pattern MASKED_TOKEN_PATTERN =
            Pattern.compile("\\[MASKED:([A-Z_]+?)(?:_[0-9a-f]+(?:_\\d+)?)?\\]", Pattern.CASE_INSENSITIVE);

    @Override
    public PrivacyPayload sanitizeForLlm(String text) {
        Map<String, String> tokenMap = new LinkedHashMap<>();
        return new PrivacyPayload(sanitizeText(text, tokenMap), tokenMap);
    }

    @Override
    public List<Message> sanitizeHistoryForLlm(List<Message> history, Map<String, String> tokenMap) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<Message> sanitized = new ArrayList<>(history.size());
        for (Message message : history) {
            if (message instanceof UserMessage) {
                UserMessage userMessage = (UserMessage) message;
                sanitized.add(new UserMessage(sanitizeText(userMessage.getText(), tokenMap)));
            } else if (message instanceof AssistantMessage) {
                AssistantMessage assistantMessage = (AssistantMessage) message;
                sanitized.add(new AssistantMessage(sanitizeText(assistantMessage.getText(), tokenMap)));
            } else {
                sanitized.add(message);
            }
        }
        return sanitized;
    }

    @Override
    public String unmaskFromLlm(String response, Map<String, String> tokenMap) {
        if (response == null || response.isBlank() || tokenMap == null || tokenMap.isEmpty()) {
            return response;
        }
        String restored = response;
        List<String> tokens = new ArrayList<>(tokenMap.keySet());
        tokens.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String token : tokens) {
            String original = tokenMap.get(token);
            if (original != null) {
                restored = restored.replace(token, original);
            }
        }

        // Fallback if model normalized token suffix but kept label.
        Matcher matcher = MASKED_TOKEN_PATTERN.matcher(restored);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String label = matcher.group(1) == null ? "" : matcher.group(1).toUpperCase(Locale.ROOT);
            String replacement = uniqueValueForLabel(label, tokenMap);
            if (replacement == null) {
                replacement = matcher.group();
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String sanitizeText(String text, Map<String, String> tokenMap) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String masked = text;
        Matcher labeled = SENSITIVE_LABELED_VALUE_PATTERN.matcher(masked);
        StringBuffer sb = new StringBuffer();
        while (labeled.find()) {
            String label = labeled.group(1);
            String value = labeled.group(2) == null ? "" : labeled.group(2).trim();
            String replacement = label + ": " + registerToken(label, value, tokenMap);
            labeled.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        labeled.appendTail(sb);
        masked = sb.toString();

        masked = maskPatternWithToken(masked, EMAIL_PATTERN, "EMAIL", tokenMap);
        masked = maskPatternWithToken(masked, PHONE_PATTERN, "PHONE", tokenMap);
        masked = maskPatternWithToken(masked, SHIP_NUMBER_PATTERN, "SHIP_NUMBER", tokenMap);
        masked = maskPatternWithToken(masked, JOB_ID_PATTERN, "JOB_ID", tokenMap);
        masked = maskLineValue(masked, ADDRESS_LINE_PATTERN, "ADDRESS", tokenMap);
        masked = maskLineValue(masked, FULL_NAME_LINE_PATTERN, "NAME", tokenMap);
        return masked;
    }

    private static String registerToken(String label, String value, Map<String, String> tokenMap) {
        if (value == null || value.isBlank()) {
            return "[MASKED]";
        }
        String normalizedLabel = label == null ? "FIELD" : label.replaceAll("\\s+", "_").toUpperCase(Locale.ROOT);
        int marker = Math.abs(Objects.hash(normalizedLabel, value.toLowerCase(Locale.ROOT)));
        String markerHex = Integer.toHexString(marker);
        if (markerHex.length() > 8) {
            markerHex = markerHex.substring(0, 8);
        }
        String token = "[MASKED:" + normalizedLabel + "_" + markerHex + "]";
        String existing = tokenMap.get(token);
        if (existing == null || existing.equals(value)) {
            tokenMap.put(token, value);
            return token;
        }
        int suffix = 2;
        String candidate = token;
        while (tokenMap.containsKey(candidate) && !tokenMap.get(candidate).equals(value)) {
            candidate = "[MASKED:" + normalizedLabel + "_" + markerHex + "_" + suffix + "]";
            suffix++;
        }
        tokenMap.put(candidate, value);
        return candidate;
    }

    private static String maskLineValue(String text, Pattern pattern, String tokenLabel, Map<String, String> tokenMap) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String label = matcher.group(1);
            String value = matcher.group(2) == null ? "" : matcher.group(2).trim();
            String replacement = label + ": " + registerToken(tokenLabel, value, tokenMap);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String maskPatternWithToken(String text, Pattern pattern, String tokenLabel, Map<String, String> tokenMap) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String value = matcher.group() == null ? "" : matcher.group().trim();
            String token = registerToken(tokenLabel, value, tokenMap);
            matcher.appendReplacement(out, Matcher.quoteReplacement(token));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String uniqueValueForLabel(String normalizedLabel, Map<String, String> tokenMap) {
        String match = null;
        String prefix = "[MASKED:" + normalizedLabel + "_";
        for (Map.Entry<String, String> e : tokenMap.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                continue;
            }
            if (match != null && !match.equals(e.getValue())) {
                return null;
            }
            match = e.getValue();
        }
        return match;
    }
}

