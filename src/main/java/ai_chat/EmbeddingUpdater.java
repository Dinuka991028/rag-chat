package ai_chat;

import ai_chat.service.AIService;
import ai_chat.service.impl.OllamaServiceImpl;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EmbeddingUpdater implements CommandLineRunner {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private AIService aiService; // will use Ollama for embeddings

    @Override
    public void run(String... args) throws Exception {
        String collectionName = "kb_documents";
        List<Document> docs = mongoTemplate.findAll(Document.class, collectionName);

        for (Document doc : docs) {
            String content = doc.getString("content");
            // generate embedding using Ollama (you can extend OllamaServiceImpl to return embeddings)
            List<Float> embedding = ((OllamaServiceImpl) aiService).getEmbedding(content);
            doc.put("embedding", embedding);
            mongoTemplate.save(doc, collectionName);
            System.out.println("Updated embedding for doc: " + content.substring(0, Math.min(50, content.length())) + "...");
        }

        System.out.println("All embeddings updated!");
    }
}