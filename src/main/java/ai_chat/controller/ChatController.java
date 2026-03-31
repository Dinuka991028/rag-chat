package ai_chat.controller;

import ai_chat.dto.ChatConversationRequest;
import ai_chat.dto.ChatConversationResponse;
import ai_chat.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
    public String chatRAG(
            @RequestBody String message,
            @RequestParam(value = "role", required = false) String role) {
        return chatService.askAIWithContext(message, role);
    }

    @PostMapping("/conversation")
    @Operation(
            summary = "Plain chat with short-term history",
            description =
                    "JSON body: message (required), conversationId (optional — omit to start a new session). "
                            + "Response includes conversationId to send on the next request.")
    public ChatConversationResponse chatWithHistory(@RequestBody ChatConversationRequest body) {
        requireMessage(body);
        validateConversationIdLength(body.getConversationId());
        return chatService.askAIWithHistory(body.getConversationId(), body.getMessage());
    }

    @PostMapping("/rag/conversation")
    @Operation(
            summary = "RAG chat with short-term history",
            description =
                    "Same session model as /chat/conversation. Follow-ups like \"yes\" use prior turns for retrieval and generation.")
    public ChatConversationResponse chatRagWithHistory(@RequestBody ChatConversationRequest body) {
        requireMessage(body);
        validateConversationIdLength(body.getConversationId());
        return chatService.askAIWithContextAndHistory(body.getConversationId(), body.getMessage(), body.getRole());
    }

    private static void requireMessage(ChatConversationRequest body) {
        if (body == null || body.getMessage() == null || body.getMessage().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required and must not be blank");
        }
    }

    private static void validateConversationIdLength(String conversationId) {
        if (conversationId != null && conversationId.trim().length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId must be at most 128 characters");
        }
    }
}
