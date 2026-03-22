package ai_chat;

import ai_chat.domain.KnowledgeDocument;
import ai_chat.repository.KnowledgeDocumentRepository;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Order(2)
public class EmbeddingUpdater implements CommandLineRunner {

    @Autowired
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Override
    public void run(String... args) {
        List<KnowledgeDocument> docs = knowledgeDocumentRepository.findAll();

        for (KnowledgeDocument doc : docs) {
            String content = doc.getContent();
            float[] vector = embeddingModel.embed(content);
            doc.setEmbedding(toFloatList(vector));
            knowledgeDocumentRepository.save(doc);
            System.out.println("Updated embedding for doc: " + content.substring(0, Math.min(50, content.length())) + "...");
        }

        System.out.println("All embeddings updated!");
    }

    private static List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add(v);
        }
        return list;
    }
}
