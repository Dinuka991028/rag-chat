package ai_chat.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/**
 * Draft KB entry proposed by unknown-query training before admin approval.
 */
@Document(collection = "kb_training_drafts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KbTrainingDraft {

    @Id
    private String id;

    private String question;

    private String normalizedQuestion;

    /** Frequency of this normalized unknown question in the processed batch. */
    private Integer count;

    private String proposedAnswer;

    /** Source tag, e.g. {@code unknown-training}. */
    private String source;

    private Status status;

    private Date createdAt;

    private Date updatedAt;

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED,
        IMPORTED
    }
}
