package ai_chat.controller;

import ai_chat.service.AIService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chat")
@Tag(name = "Chat API", description = "AI Chat endpoints")
public class ChatController {

    private final AIService aiService;

    @Autowired
    public ChatController(AIService aiService) {
        this.aiService = aiService;
    }

    @PostMapping
    @Operation(summary = "Send a message to AI and get response")
    public String chat(@RequestBody String message) {
        return aiService.askAI(message);
    }

    @PostMapping("/rag")
    @Operation(summary = "Send a message using RAG with AI")
    public String chatRAG(@RequestBody String message) {
        return aiService.askAIWithContext(message);
    }
}