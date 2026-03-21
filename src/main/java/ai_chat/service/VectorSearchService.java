package ai_chat.service;

import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

@Service
public class VectorSearchService {

    /**
     * Compute cosine similarity between two float vectors
     */
    public double cosineSimilarity(List<Float> v1, List<Float> v2) {
        if (v1 == null || v2 == null || v1.size() != v2.size()) return 0.0;
        double dot = 0.0, norm1 = 0.0, norm2 = 0.0;
        for (int i = 0; i < v1.size(); i++) {
            dot += v1.get(i) * v2.get(i);
            norm1 += v1.get(i) * v1.get(i);
            norm2 += v2.get(i) * v2.get(i);
        }
        if (norm1 == 0.0 || norm2 == 0.0) return 0.0;
        return dot / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    /**
     * Get top N most similar documents based on cosine similarity
     */
    public List<Document> getTopDocuments(List<Document> docs, List<Float> queryEmbedding, int topN) {
        if (docs == null || docs.isEmpty() || queryEmbedding == null) return Collections.emptyList();

        // Use a min-heap to keep top N docs efficiently
        PriorityQueue<ScoredDoc> heap = new PriorityQueue<>(topN, (a, b) -> Double.compare(a.score, b.score));

        for (Document doc : docs) {
            List<Float> embedding = convertToFloatList(doc.get("embedding"));
            if (embedding == null || embedding.size() != queryEmbedding.size()) continue;

            double score = cosineSimilarity(queryEmbedding, embedding);
            if (heap.size() < topN) {
                heap.offer(new ScoredDoc(score, doc));
            } else if (score > heap.peek().score) {
                heap.poll();
                heap.offer(new ScoredDoc(score, doc));
            }
        }

        // Extract documents sorted by descending score
        List<Document> topDocs = new ArrayList<>();
        while (!heap.isEmpty()) topDocs.add(0, heap.poll().doc); // insert at start to reverse order

        return topDocs;
    }

    // Helper: safely convert Object to List<Float>
    @SuppressWarnings("unchecked")
    private List<Float> convertToFloatList(Object obj) {
        if (!(obj instanceof List<?> list)) return null;
        List<Float> floatList = new ArrayList<>();
        for (Object n : list) {
            if (n instanceof Number number) {
                floatList.add(number.floatValue());
            } else {
                return null;
            }
        }
        return floatList;
    }

    // Internal helper class for scoring
    private static class ScoredDoc {
        double score;
        Document doc;
        public ScoredDoc(double score, Document doc) {
            this.score = score;
            this.doc = doc;
        }
    }
}