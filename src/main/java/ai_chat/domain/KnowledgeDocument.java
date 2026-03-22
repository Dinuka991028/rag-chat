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
 * Knowledge-base chunk stored in MongoDB collection {@code kb_documents}.
 */
@Document(collection = "kb_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeDocument {

    @Id
    private String id;

    private String content;

    private String category;

    private String source;

    /** Order within the SRS ingest; optional, for debugging. */
    private Integer chunkIndex;

    /** Best-effort section title from the SRS (e.g. 2.2.1 Portal Login). */
    private String sectionHeading;

    /** Embedding vector; set when rows are ingested via {@link ai_chat.vectorstore.LocalMongoVectorStore#add}. */
    private List<Float> embedding;
}
