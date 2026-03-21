package ai_chat.service;

public interface AIService {
    String askAI(String prompt);
    String askAIWithContext(String prompt); // new method for RAG
}