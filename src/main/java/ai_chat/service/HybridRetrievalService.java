package ai_chat.service;

import ai_chat.domain.KnowledgeDocument;
import ai_chat.domain.OfficerKnowledgeDocument;
import ai_chat.vectorstore.LocalMongoVectorStore;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.TextIndexDefinition;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.data.mongodb.core.query.TextQuery;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hybrid retrieval: combines vector search and MongoDB text-index keyword search with reciprocal-rank fusion.
 */
@Service
public class HybridRetrievalService {

    private static final int RRF_K = 60;
    private static final Pattern SHIP_NUMBER_PATTERN = Pattern.compile("\\b[A-Z]-\\d{3,}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern JOB_ID_PATTERN = Pattern.compile("\\b\\d{6,}\\b");

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
        return retrieve(query, vectorTopK, similarityThreshold, null);
    }

    public List<Document> retrieve(String query, int vectorTopK, double similarityThreshold, String role) {
        int effectiveVectorTopK = "officer".equals(normalizeRole(role))
                ? Math.max(vectorTopK * 4, vectorTopK + 20)
                : vectorTopK;
        SearchRequest vectorRequest = SearchRequest.builder()
                .query(query)
                .topK(effectiveVectorTopK)
                .similarityThreshold(similarityThreshold)
                .build();
        List<Document> vector = vectorStore instanceof LocalMongoVectorStore localMongoVectorStore
                ? localMongoVectorStore.similaritySearch(vectorRequest, role)
                : vectorStore.similaritySearch(vectorRequest);
        List<Document> scopedVector = filterByRole(vector, role);
        if (!hybridEnabled || query == null || query.isBlank()) {
            return cap(scopedVector, vectorTopK);
        }
        List<Document> keyword = keywordRetrieve(query, keywordTopK, role);
        List<Document> fused = fuse(scopedVector, keyword);
        return cap(fused, Math.max(vectorTopK, keywordTopK));
    }

    private List<Document> keywordRetrieve(String query, int topK, String role) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<Document> out = new ArrayList<>();
        String shipNumber = extractShipNumber(query);
        if (shipNumber != null) {
            Query shipQuery = new Query();
            shipQuery.addCriteria(Criteria.where("content").regex("\\b" + Pattern.quote(shipNumber) + "\\b", "i"));
            Criteria roleCriteria = roleCriteria(role);
            if (roleCriteria != null) {
                shipQuery.addCriteria(roleCriteria);
            }
            shipQuery.limit(topK);
            if ("officer".equals(normalizeRole(role))) {
                List<OfficerKnowledgeDocument> byShip = mongoTemplate.find(shipQuery, OfficerKnowledgeDocument.class);
                out.addAll(byShip.stream().map(HybridRetrievalService::toSpringDocument).toList());
            } else {
                List<KnowledgeDocument> byShip = mongoTemplate.find(shipQuery, KnowledgeDocument.class);
                out.addAll(byShip.stream().map(HybridRetrievalService::toSpringDocument).toList());
            }
        }
        String jobId = extractJobId(query);
        if (jobId != null && "officer".equals(normalizeRole(role))) {
            Query jobQuery = new Query();
            jobQuery.addCriteria(Criteria.where("content").regex("\\bJob\\s+ID\\s*:\\s*" + Pattern.quote(jobId) + "\\b", "i"));
            Criteria roleCriteria = roleCriteria(role);
            if (roleCriteria != null) {
                jobQuery.addCriteria(roleCriteria);
            }
            jobQuery.limit(topK);
            List<OfficerKnowledgeDocument> byJob = mongoTemplate.find(jobQuery, OfficerKnowledgeDocument.class);
            for (Document d : byJob.stream().map(HybridRetrievalService::toSpringDocument).toList()) {
                if (!containsDocId(out, d.getId())) {
                    out.add(d);
                }
            }
        }
        TextCriteria criteria = TextCriteria.forDefaultLanguage().matching(query);
        Query textQuery = TextQuery.queryText(criteria)
                .sortByScore()
                .includeScore()
                .with(Sort.by(Sort.Direction.DESC, "score"))
                .limit(topK);
        Criteria roleCriteria = roleCriteria(role);
        if (roleCriteria != null) {
            textQuery.addCriteria(roleCriteria);
        }
        List<Document> keywordDocs;
        if ("officer".equals(normalizeRole(role))) {
            List<OfficerKnowledgeDocument> rows = mongoTemplate.find(textQuery, OfficerKnowledgeDocument.class);
            keywordDocs = rows.stream().map(HybridRetrievalService::toSpringDocument).toList();
        } else {
            List<KnowledgeDocument> rows = mongoTemplate.find(textQuery, KnowledgeDocument.class);
            keywordDocs = rows.stream().map(HybridRetrievalService::toSpringDocument).toList();
        }
        for (Document d : keywordDocs) {
            if (containsDocId(out, d.getId())) {
                continue;
            }
            out.add(d);
        }
        return out;
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

    private static List<Document> filterByRole(List<Document> docs, String role) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        String normalizedRole = normalizeRole(role);
        List<Document> out = new ArrayList<>(docs.size());
        for (Document d : docs) {
            Map<String, Object> m = d.getMetadata();
            String category = m == null || m.get("category") == null ? "" : String.valueOf(m.get("category"));
            String source = m == null || m.get("source") == null ? "" : String.valueOf(m.get("source"));
            String audienceRole = m == null || m.get("audienceRole") == null ? "" : String.valueOf(m.get("audienceRole"));
            boolean officerDoc = "officer".equalsIgnoreCase(audienceRole)
                    || "OfficerJobSummary".equalsIgnoreCase(category)
                    || source.startsWith("officer-");
            if ("officer".equals(normalizedRole) && officerDoc) {
                out.add(d);
            } else if (!"officer".equals(normalizedRole) && !officerDoc) {
                out.add(d);
            }
        }
        return out;
    }

    private static Criteria roleCriteria(String role) {
        if ("officer".equals(normalizeRole(role))) {
            return new Criteria().orOperator(
                    Criteria.where("audienceRole").is("officer"),
                    Criteria.where("category").is("OfficerJobSummary"),
                    Criteria.where("source").regex("^officer-", "i"));
        }
        return new Criteria().norOperator(
                Criteria.where("audienceRole").is("officer"),
                Criteria.where("category").is("OfficerJobSummary"),
                Criteria.where("source").regex("^officer-", "i"));
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "customer";
        }
        String r = role.trim().toLowerCase(Locale.ROOT);
        return "officer".equals(r) ? "officer" : "customer";
    }

    private static String extractShipNumber(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        Matcher m = SHIP_NUMBER_PATTERN.matcher(query);
        if (!m.find()) {
            return null;
        }
        return m.group().toUpperCase(Locale.ROOT);
    }

    private static String extractJobId(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        Matcher m = JOB_ID_PATTERN.matcher(query);
        if (!m.find()) {
            return null;
        }
        return m.group();
    }

    private static boolean containsDocId(List<Document> docs, String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        for (Document d : docs) {
            if (id.equals(d.getId())) {
                return true;
            }
        }
        return false;
    }

    private static List<Document> cap(List<Document> docs, int max) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        if (docs.size() <= max) {
            return docs;
        }
        return docs.subList(0, max);
    }

    private void ensureTextIndex() {
        TextIndexDefinition idx = new TextIndexDefinition.TextIndexDefinitionBuilder()
                .onField("content", 10F)
                .onField("sectionHeading", 5F)
                .onField("category", 2F)
                .build();
        mongoTemplate.indexOps(KnowledgeDocument.class).createIndex(idx);
        mongoTemplate.indexOps(OfficerKnowledgeDocument.class).createIndex(idx);
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
        if (kd.getAudienceRole() != null) {
            meta.put("audienceRole", kd.getAudienceRole());
        }
        return new Document(kd.getId(), kd.getContent(), meta);
    }

    private static Document toSpringDocument(OfficerKnowledgeDocument kd) {
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
        if (kd.getAudienceRole() != null) {
            meta.put("audienceRole", kd.getAudienceRole());
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
