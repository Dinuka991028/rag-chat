package ai_chat.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

/**
 * Officer knowledge-base chunk stored in MongoDB collection {@code kb_documents_officer}.
 */
@Document(collection = "kb_documents_officer")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OfficerKnowledgeDocument {

    @Id
    private String id;

    private String content;

    private String category;

    private String source;

    /** Audience for this KB chunk: customer or officer. */
    private String audienceRole;

    private Integer chunkIndex;

    private String sectionHeading;

    private List<Float> embedding;

    private String embeddingModel;

    private String embeddingVersion;
}
