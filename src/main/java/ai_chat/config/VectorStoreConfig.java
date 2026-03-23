package ai_chat.config;

import ai_chat.repository.KnowledgeDocumentRepository;
import ai_chat.vectorstore.LocalMongoVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VectorStoreConfig {

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel,
                                   KnowledgeDocumentRepository knowledgeDocumentRepository,
                                   @Value("${conf.kb.embedding-metadata.model-tag:${conf.ai.embedding-provider}}") String embeddingModelTag,
                                   @Value("${conf.kb.embedding-metadata.version:v1}") String embeddingVersion,
                                   @Value("${conf.kb.embedding-compatibility.strict:false}") boolean strictEmbeddingCompatibility) {
        return new LocalMongoVectorStore(
                embeddingModel,
                knowledgeDocumentRepository,
                embeddingModelTag,
                embeddingVersion,
                strictEmbeddingCompatibility);
    }
}
