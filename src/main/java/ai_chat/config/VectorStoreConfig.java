package ai_chat.config;

import ai_chat.repository.KnowledgeDocumentRepository;
import ai_chat.vectorstore.LocalMongoVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VectorStoreConfig {

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel,
                                   KnowledgeDocumentRepository knowledgeDocumentRepository) {
        return new LocalMongoVectorStore(embeddingModel, knowledgeDocumentRepository);
    }
}
