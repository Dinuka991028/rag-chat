package ai_chat.dto;

import lombok.Data;

/** Admin-authored knowledge row (embedded and stored like seed data). */
@Data
public class AddKnowledgeRequest {

    private String content;
    private String category;
    private String source;
    /** Optional; prepended to content for better embedding matches. */
    private String title;
}
