package ai_chat.service;

import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class VectorSearchService {

    // Cosine similarity
    private double cosineSimilarity(List<Float> v1, List<Float> v2) {
        double dot = 0.0, norm1 = 0.0, norm2 = 0.0;
        for (int i = 0; i < v1.size(); i++) {
            dot += v1.get(i) * v2.get(i);
            norm1 += v1.get(i) * v1.get(i);
            norm2 += v2.get(i) * v2.get(i);
        }
        return dot / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    // Get top N relevant documents
    public List<Document> getTopDocuments(List<Document> docs, List<Float> queryEmbedding, int topN) {
        TreeMap<Double, Document> sorted = new TreeMap<>(Collections.reverseOrder());
        for (Document doc : docs) {
            List<Float> embedding = (List<Float>) doc.get("embedding");
            if (embedding != null) {
                double sim = cosineSimilarity(queryEmbedding, embedding);
                sorted.put(sim, doc);
            }
        }

        List<Document> topDocs = new ArrayList<>();
        int count = 0;
        for (Document d : sorted.values()) {
            topDocs.add(d);
            count++;
            if (count >= topN) break;
        }

        return topDocs;
    }
}