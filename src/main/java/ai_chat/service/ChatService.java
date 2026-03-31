package ai_chat.service;

import ai_chat.dto.ChatConversationResponse;

public interface ChatService {
    String askAI(String prompt);

    String askAIWithContext(String prompt);
    String askAIWithContext(String prompt, String role);

    ChatConversationResponse askAIWithHistory(String conversationId, String message);

    ChatConversationResponse askAIWithContextAndHistory(String conversationId, String message);
    ChatConversationResponse askAIWithContextAndHistory(String conversationId, String message, String role);
}
