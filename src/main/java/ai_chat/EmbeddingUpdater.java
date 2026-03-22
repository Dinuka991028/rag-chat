package ai_chat;

import ai_chat.domain.KnowledgeDocument;
import ai_chat.repository.KnowledgeDocumentRepository;
import ai_chat.service.AIService;
import ai_chat.service.impl.OllamaServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(2)
public class EmbeddingUpdater implements CommandLineRunner {

    @Autowired
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Autowired
    private AIService aiService;

    @Override
    public void run(String... args) {
        List<KnowledgeDocument> docs = knowledgeDocumentRepository.findAll();

        for (KnowledgeDocument doc : docs) {
            String content = doc.getContent();
            List<Float> embedding = ((OllamaServiceImpl) aiService).getEmbedding(content);
            doc.setEmbedding(embedding);
            knowledgeDocumentRepository.save(doc);
            System.out.println("Updated embedding for doc: " + content.substring(0, Math.min(50, content.length())) + "...");
        }

        System.out.println("All embeddings updated!");
    }
}
