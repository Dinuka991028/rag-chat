package ai_chat.service;

import ai_chat.domain.KnowledgeDocument;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.TextIndexDefinition;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.data.mongodb.core.query.TextQuery;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hybrid retrieval: combines vector search and MongoDB text-index keyword search with reciprocal-rank fusion.
 */
@Service
public class HybridRetrievalService {

    private static final int RRF_K = 60;

    private final VectorStore vectorStore;
    private final MongoTemplate mongoTemplate;
    private final boolean hybridEnabled;
    private final int keywordTopK;
    private final double vectorWeight;
    private final double keywordWeight;

    public HybridRetrievalService(
            VectorStore vectorStore,
            MongoTemplate mongoTemplate,
            @Value("${conf.rag.hybrid.enabled:true}") boolean hybridEnabled,
            @Value("${conf.rag.hybrid.keyword-top-k:20}") int keywordTopK,
            @Value("${conf.rag.hybrid.vector-weight:1.0}") double vectorWeight,
            @Value("${conf.rag.hybrid.keyword-weight:0.8}") double keywordWeight) {
        this.vectorStore = vectorStore;
        this.mongoTemplate = mongoTemplate;
        this.hybridEnabled = hybridEnabled;
        this.keywordTopK = Math.max(1, keywordTopK);
        this.vectorWeight = Math.max(0.0, vectorWeight);
        this.keywordWeight = Math.max(0.0, keywordWeight);
        ensureTextIndex();
    }

    public List<Document> retrieve(String query, int vectorTopK, double similarityThreshold) {
        List<Document> vector = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(vectorTopK)
                        .similarityThreshold(similarityThreshold)
                        .build());
        if (!hybridEnabled || query == null || query.isBlank()) {
            return vector == null ? List.of() : vector;
        }
        List<Document> keyword = keywordRetrieve(query, keywordTopK);
        return fuse(vector, keyword);
    }

    private List<Document> keywordRetrieve(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        TextCriteria criteria = TextCriteria.forDefaultLanguage().matching(query);
        Query textQuery = TextQuery.queryText(criteria)
                .sortByScore()
                .includeScore()
                .with(Sort.by(Sort.Direction.DESC, "score"))
                .limit(topK);
        List<KnowledgeDocument> rows = mongoTemplate.find(textQuery, KnowledgeDocument.class);
        return rows.stream().map(HybridRetrievalService::toSpringDocument).toList();
    }

    private List<Document> fuse(List<Document> vector, List<Document> keyword) {
        Map<String, DocFusion> combined = new LinkedHashMap<>();
        addWithRrf(combined, vector, vectorWeight);
        addWithRrf(combined, keyword, keywordWeight);
        return combined.values().stream()
                .sorted(Comparator.comparingDouble((DocFusion d) -> d.score).reversed())
                .map(d -> d.document)
                .toList();
    }

    private static void addWithRrf(Map<String, DocFusion> out, List<Document> docs, double weight) {
        if (docs == null || docs.isEmpty() || weight <= 0.0) {
            return;
        }
        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            String id = stableDocId(d, i);
            DocFusion acc = out.computeIfAbsent(id, __ -> new DocFusion(d));
            acc.score += weight / (RRF_K + (i + 1));
        }
    }

    private static String stableDocId(Document d, int i) {
        if (d.getId() != null && !d.getId().isBlank()) {
            return d.getId();
        }
        String text = d.getText() == null ? "" : d.getText();
        return "doc-" + i + "-" + Integer.toHexString(text.hashCode());
    }

    private void ensureTextIndex() {
        TextIndexDefinition idx = new TextIndexDefinition.TextIndexDefinitionBuilder()
                .onField("content", 10F)
                .onField("sectionHeading", 5F)
                .onField("category", 2F)
                .build();
        mongoTemplate.indexOps(KnowledgeDocument.class).createIndex(idx);
    }

    private static Document toSpringDocument(KnowledgeDocument kd) {
        Map<String, Object> meta = new HashMap<>();
        if (kd.getCategory() != null) {
            meta.put("category", kd.getCategory());
        }
        if (kd.getSource() != null) {
            meta.put("source", kd.getSource());
        }
        if (kd.getChunkIndex() != null) {
            meta.put("chunkIndex", kd.getChunkIndex());
        }
        if (kd.getSectionHeading() != null) {
            meta.put("sectionHeading", kd.getSectionHeading());
        }
        return new Document(kd.getId(), kd.getContent(), meta);
    }

    private static final class DocFusion {
        private final Document document;
        private double score;

        private DocFusion(Document document) {
            this.document = document;
        }
    }
}
