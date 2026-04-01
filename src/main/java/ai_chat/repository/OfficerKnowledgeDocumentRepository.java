package ai_chat.repository;

import ai_chat.domain.OfficerKnowledgeDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OfficerKnowledgeDocumentRepository extends MongoRepository<OfficerKnowledgeDocument, String> {
}
