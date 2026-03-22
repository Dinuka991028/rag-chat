package ai_chat.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/**
 * Customer question logged when RAG could not retrieve a confident match ({@code unknown_queries}).
 */
@Document(collection = "unknown_queries")
@Getter
@Setter
public class UnknownQuery {

    @Id
    private String id;

    private String question;

    private Date createdAt;
}
