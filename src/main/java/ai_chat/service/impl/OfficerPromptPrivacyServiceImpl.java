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
                    "(?im)\\b(customer\\s*id|customer\\s*name|full\\s*name|name|service\\s*name|ship\\s*name|vessel\\s*name|email|e-?mail|phone|mobile|contact\\s*number|national\\s*id|id\\s*number|passport\\s*number|ship\\s*number|job\\s*id|application\\s*id|address|home\\s*address|mailing\\s*address)\\b\\s*[:#-]\\s*([^\\n\\r]+)");
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("\\b(?:\\+?\\d[\\d\\s-]{7,}\\d)\\b");
    private static final Pattern SHIP_NUMBER_PATTERN = Pattern.compile("\\b(?:BH|J)-\\d{3,}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern JOB_ID_PATTERN = Pattern.compile("\\b\\d{6,}\\b");
    private static final Pattern ADDRESS_LINE_PATTERN =
            Pattern.compile("(?im)\\b(address|location|residence)\\b\\s*[:#-]\\s*([^\\n\\r]+)");
    private static final Pattern FULL_NAME_LINE_PATTERN =
            Pattern.compile("(?im)\\b(customer\\s*name|full\\s*name|applicant\\s*name|owner\\s*name|name)\\b\\s*[:#-]\\s*([^\\n\\r]+)");
    /** Any bracketed MASKED token the model might echo from context (label + optional hash + optional disambiguator). */
    private static final Pattern MASKED_TOKEN_PATTERN =
            Pattern.compile("\\[MASKED:([A-Za-z0-9_]+)_([0-9a-fA-F]+)(?:_(\\d+))?\\]");
    private static final Pattern ANY_BRACKET_TOKEN_PATTERN =
            Pattern.compile("\\[[A-Za-z0-9:_-]{4,}\\]");

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
        String restored = normalizeForUnmask(response);
        List<String> tokens = new ArrayList<>(tokenMap.keySet());
        tokens.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String token : tokens) {
            String original = tokenMap.get(token);
            if (original != null) {
                restored = restored.replace(token, original);
                if (!token.equals(token.toUpperCase(Locale.ROOT))) {
                    restored = restored.replace(token.toUpperCase(Locale.ROOT), original);
                }
            }
        }

        // Second pass: hex case-insensitive + structural variants of the same token.
        Matcher matcher = MASKED_TOKEN_PATTERN.matcher(restored);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String full = matcher.group(0);
            String label = matcher.group(1) == null ? "" : matcher.group(1).toUpperCase(Locale.ROOT);
            String hex = matcher.group(2) == null ? "" : matcher.group(2).toLowerCase(Locale.ROOT);
            String disambig = matcher.group(3);
            String replacement = resolveMaskedToken(full, label, hex, disambig, tokenMap);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return cleanupUnresolvedMasks(out.toString(), tokenMap);
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
            putTokenAliases(token, value, tokenMap);
            return token;
        }
        int suffix = 2;
        String candidate = token;
        while (tokenMap.containsKey(candidate) && !tokenMap.get(candidate).equals(value)) {
            candidate = "[MASKED:" + normalizedLabel + "_" + markerHex + "_" + suffix + "]";
            suffix++;
        }
        putTokenAliases(candidate, value, tokenMap);
        return candidate;
    }

    private static void putTokenAliases(String token, String value, Map<String, String> tokenMap) {
        tokenMap.put(token, value);
        int open = token.indexOf('[');
        int close = token.indexOf(']');
        if (open >= 0 && close > open) {
            String inner = token.substring(open + 1, close);
            if (inner.contains("_")) {
                tokenMap.put(inner, value);
            }
        }
        Matcher variant = Pattern.compile("\\[MASKED:([A-Za-z0-9_]+)_([0-9a-f]+)((?:_\\d+)?)\\]").matcher(token);
        if (variant.matches()) {
            String alt = "[MASKED:" + variant.group(1) + "_" + variant.group(2).toUpperCase(Locale.ROOT)
                    + (variant.group(3) == null ? "" : variant.group(3)) + "]";
            if (!alt.equals(token)) {
                tokenMap.put(alt, value);
            }
        }
    }

    private static String normalizeForUnmask(String response) {
        if (response == null) {
            return "";
        }
        String t = response;
        t = t.replace('\u200B', ' ')
                .replace('\u200C', ' ')
                .replace('\u200D', ' ')
                .replace('\uFEFF', ' ');
        return t;
    }

    private static String resolveMaskedToken(String full, String label, String hex, String disambig, Map<String, String> tokenMap) {
        if (full == null) {
            return "";
        }
        if (tokenMap.containsKey(full)) {
            return tokenMap.get(full);
        }
        String canonical = disambig == null
                ? "[MASKED:" + label + "_" + hex + "]"
                : "[MASKED:" + label + "_" + hex + "_" + disambig + "]";
        if (tokenMap.containsKey(canonical)) {
            return tokenMap.get(canonical);
        }
        for (Map.Entry<String, String> e : tokenMap.entrySet()) {
            if (e.getKey().equalsIgnoreCase(canonical)) {
                return e.getValue();
            }
        }
        String innerCanon = canonical.substring(1, canonical.length() - 1);
        if (tokenMap.containsKey(innerCanon)) {
            return tokenMap.get(innerCanon);
        }
        String canonicalUpperHex = disambig == null
                ? "[MASKED:" + label + "_" + hex.toUpperCase(Locale.ROOT) + "]"
                : "[MASKED:" + label + "_" + hex.toUpperCase(Locale.ROOT) + "_" + disambig + "]";
        if (tokenMap.containsKey(canonicalUpperHex)) {
            return tokenMap.get(canonicalUpperHex);
        }
        String hexUpper = hex.toUpperCase(Locale.ROOT);
        for (Map.Entry<String, String> e : tokenMap.entrySet()) {
            String k = e.getKey();
            if (!k.toUpperCase(Locale.ROOT).contains("MASKED:" + label + "_")) {
                continue;
            }
            if (k.contains("_" + hex) || k.contains("_" + hexUpper)) {
                return e.getValue();
            }
        }
        return full;
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

    private static String cleanupUnresolvedMasks(String text, Map<String, String> tokenMap) {
        String out = text;
        String shipName = uniqueValueForLabelContains(tokenMap, "SHIP_NAME", "VESSEL_NAME");
        String serviceName = uniqueValueForLabelContains(tokenMap, "SERVICE_NAME");
        String customerName = uniqueValueForLabelContains(tokenMap, "CUSTOMER_NAME", "FULL_NAME", "NAME");
        String customerId = uniqueValueForLabelContains(tokenMap, "CUSTOMER_ID", "ID_NUMBER", "NATIONAL_ID");

        if (shipName != null) {
            out = out.replaceAll("(?i)(ship\\s*name\\s*[:\\-]?\\s*)(\\[[^\\]]+\\])", "$1" + Matcher.quoteReplacement(shipName));
            out = out.replaceAll("(?i)(vessel\\s*name\\s*[:\\-]?\\s*)(\\[[^\\]]+\\])", "$1" + Matcher.quoteReplacement(shipName));
        }
        if (serviceName != null) {
            out = out.replaceAll("(?i)(service\\s*name\\s*[:\\-]?\\s*)(\\[[^\\]]+\\])", "$1" + Matcher.quoteReplacement(serviceName));
        }
        if (customerName != null) {
            out = out.replaceAll("(?i)(customer\\s*name\\s*[:\\-]?\\s*)(\\[[^\\]]+\\])", "$1" + Matcher.quoteReplacement(customerName));
        }
        if (customerId != null) {
            out = out.replaceAll("(?i)(customer\\s*id\\s*[:\\-]?\\s*)(\\[[^\\]]+\\])", "$1" + Matcher.quoteReplacement(customerId));
        }

        // Final safety net: do not leak synthetic placeholder-like bracket tokens to officer.
        Matcher leftover = ANY_BRACKET_TOKEN_PATTERN.matcher(out);
        StringBuffer sb = new StringBuffer();
        while (leftover.find()) {
            String token = leftover.group();
            if (tokenMap.containsKey(token)) {
                leftover.appendReplacement(sb, Matcher.quoteReplacement(tokenMap.get(token)));
                continue;
            }
            if (token.toUpperCase(Locale.ROOT).contains("MASKED")) {
                leftover.appendReplacement(sb, "value");
                continue;
            }
            leftover.appendReplacement(sb, token);
        }
        leftover.appendTail(sb);
        return sb.toString();
    }

    private static String uniqueValueForLabelContains(Map<String, String> tokenMap, String... labelHints) {
        String found = null;
        for (Map.Entry<String, String> e : tokenMap.entrySet()) {
            String keyUpper = e.getKey().toUpperCase(Locale.ROOT);
            boolean match = false;
            for (String hint : labelHints) {
                if (keyUpper.contains("MASKED:" + hint + "_")) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                continue;
            }
            if (found == null) {
                found = e.getValue();
            } else if (!found.equals(e.getValue())) {
                return null;
            }
        }
        return found;
    }

}

