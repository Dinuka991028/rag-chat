package ai_chat.service;

import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;

public interface OfficerPromptPrivacyService {

    PrivacyPayload sanitizeForLlm(String text);

    List<Message> sanitizeHistoryForLlm(List<Message> history, Map<String, String> tokenMap);

    String unmaskFromLlm(String response, Map<String, String> tokenMap);

    record PrivacyPayload(String text, Map<String, String> tokenMap) {}
}

