package ai_chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Chat with optional short-term history: reuse {@code conversationId} from a prior response, or omit to start a new session.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatConversationRequest {

    /** Client-generated or from prior response; null/blank starts a new conversation. */
    private String conversationId;

    private String message;

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
