package ai_chat.service;

public interface ChatService {
    String askAI(String prompt);
    String askAIWithContext(String prompt);
}
