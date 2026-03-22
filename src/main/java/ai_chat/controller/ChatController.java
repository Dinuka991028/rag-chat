package ai_chat.controller;

import ai_chat.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chat")
@Tag(name = "Chat API", description = "AI Chat endpoints")
public class ChatController {

    private final ChatService chatService;

    @Autowired
    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    @Operation(summary = "Send a message to AI and get response")
    public String chat(@RequestBody String message) {
        return chatService.askAI(message);
    }

    @PostMapping("/rag")
    @Operation(summary = "Send a message using RAG with AI")
    public String chatRAG(@RequestBody String message) {
        return chatService.askAIWithContext(message);
    }
}