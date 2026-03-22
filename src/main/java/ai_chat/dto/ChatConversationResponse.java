package ai_chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Reply plus {@code conversationId} to send on the next turn for short-term dialogue continuity. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatConversationResponse {

    private String conversationId;
    private String reply;

    public ChatConversationResponse() {}

    public ChatConversationResponse(String conversationId, String reply) {
        this.conversationId = conversationId;
        this.reply = reply;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }
}
