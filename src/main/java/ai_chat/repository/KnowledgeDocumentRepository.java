package ai_chat.repository;

import ai_chat.domain.KnowledgeDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface KnowledgeDocumentRepository extends MongoRepository<KnowledgeDocument, String> {
}
