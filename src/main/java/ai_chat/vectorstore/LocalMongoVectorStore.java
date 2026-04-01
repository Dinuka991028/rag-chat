package ai_chat.vectorstore;

import ai_chat.domain.KnowledgeDocument;
import ai_chat.domain.OfficerKnowledgeDocument;
import ai_chat.repository.KnowledgeDocumentRepository;
import ai_chat.repository.OfficerKnowledgeDocumentRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
/**
 * Spring AI {@link VectorStore} backed by self-hosted MongoDB ({@link KnowledgeDocument} rows).
 * Uses cosine similarity in-process (no Atlas Vector Search).
 */
public class LocalMongoVectorStore implements VectorStore {
    private static final Logger log = LoggerFactory.getLogger(LocalMongoVectorStore.class);

    private static final String META_CATEGORY = "category";
    private static final String META_SOURCE = "source";
    private static final String META_CHUNK_INDEX = "chunkIndex";
    private static final String META_SECTION_HEADING = "sectionHeading";
    private static final String META_AUDIENCE_ROLE = "audienceRole";
    private static final String META_EMBEDDING_MODEL = "embeddingModel";
    private static final String META_EMBEDDING_VERSION = "embeddingVersion";

    private final EmbeddingModel embeddingModel;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final OfficerKnowledgeDocumentRepository officerKnowledgeDocumentRepository;
    private final String activeEmbeddingModelTag;
    private final String activeEmbeddingVersion;
    private final boolean strictEmbeddingCompatibility;

    public LocalMongoVectorStore(EmbeddingModel embeddingModel,
                                 KnowledgeDocumentRepository knowledgeDocumentRepository,
                                 OfficerKnowledgeDocumentRepository officerKnowledgeDocumentRepository,
                                 String activeEmbeddingModelTag,
                                 String activeEmbeddingVersion,
                                 boolean strictEmbeddingCompatibility) {
        this.embeddingModel = embeddingModel;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.officerKnowledgeDocumentRepository = officerKnowledgeDocumentRepository;
        this.activeEmbeddingModelTag = normalize(activeEmbeddingModelTag);
        this.activeEmbeddingVersion = normalize(activeEmbeddingVersion);
        this.strictEmbeddingCompatibility = strictEmbeddingCompatibility;
    }

    @Override
    public void add(List<Document> documents) {
        for (Document doc : documents) {
            if (!doc.isText() || doc.getText() == null) {
                continue;
            }
            KnowledgeDocument entity = new KnowledgeDocument();
            entity.setId(doc.getId());
            entity.setContent(doc.getText());
            Map<String, Object> meta = doc.getMetadata();
            if (meta != null) {
                Object c = meta.get(META_CATEGORY);
                Object s = meta.get(META_SOURCE);
                if (c != null) {
                    entity.setCategory(String.valueOf(c));
                }
                if (s != null) {
                    entity.setSource(String.valueOf(s));
                }
                Object audienceRole = meta.get(META_AUDIENCE_ROLE);
                if (audienceRole != null) {
                    String role = String.valueOf(audienceRole).trim();
                    if (!role.isEmpty()) {
                        entity.setAudienceRole(role);
                    }
                }
                Object chunkIdx = meta.get(META_CHUNK_INDEX);
                if (chunkIdx instanceof Number) {
                    entity.setChunkIndex(((Number) chunkIdx).intValue());
                } else if (chunkIdx != null) {
                    try {
                        entity.setChunkIndex(Integer.parseInt(String.valueOf(chunkIdx)));
                    } catch (NumberFormatException ignored) {
                        // leave null
                    }
                }
                Object heading = meta.get(META_SECTION_HEADING);
                if (heading != null) {
                    String h = String.valueOf(heading).trim();
                    if (!h.isEmpty()) {
                        entity.setSectionHeading(h);
                    }
                }
            }
            float[] vector = embeddingModel.embed(doc.getText());
            entity.setEmbedding(toFloatList(vector));
            entity.setEmbeddingModel(activeEmbeddingModelTag);
            entity.setEmbeddingVersion(activeEmbeddingVersion);
            if ("officer".equalsIgnoreCase(entity.getAudienceRole())) {
                officerKnowledgeDocumentRepository.save(toOfficerEntity(entity));
            } else {
                knowledgeDocumentRepository.save(entity);
            }
        }
    }

    @Nullable
    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        return similaritySearch(request, null);
    }

    @Nullable
    public List<Document> similaritySearch(SearchRequest request, String role) {
        if (request.hasFilterExpression()) {
            throw new UnsupportedOperationException(
                    "Metadata filter expressions are not supported by LocalMongoVectorStore yet");
        }
        String query = request.getQuery();
        if (query == null || query.isBlank()) {
            return List.of();
        }

        float[] queryVector = embeddingModel.embed(query);
        List<Float> queryFloats = toFloatList(queryVector);

        List<Scored> scored = new ArrayList<>();
        String normalizedRole = normalizeRole(role);
        if ("officer".equals(normalizedRole)) {
            List<OfficerKnowledgeDocument> officerDocs = officerKnowledgeDocumentRepository.findAll();
            log.debug("Vector search role={} collection=kb_documents_officer rawCandidates={}",
                    normalizedRole, officerDocs.size());
            for (OfficerKnowledgeDocument kd : officerDocs) {
                addScoredOfficer(scored, kd, queryFloats);
            }
        } else {
            List<KnowledgeDocument> customerDocs = knowledgeDocumentRepository.findAll();
            log.debug("Vector search role={} collection=kb_documents rawCandidates={}",
                    normalizedRole, customerDocs.size());
            for (KnowledgeDocument kd : customerDocs) {
                addScoredCustomer(scored, kd, queryFloats);
            }
        }

        scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());

        double threshold = request.getSimilarityThreshold();
        boolean applyThreshold = threshold > SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL;

        int topK = request.getTopK();
        List<Document> out = new ArrayList<>();
        for (Scored s : scored) {
            if (applyThreshold && s.score < threshold) {
                continue;
            }
            out.add(s.doc);
            if (out.size() >= topK) {
                break;
            }
        }
        log.debug("Vector search role={} scoredCandidates={} topK={} returned={} threshold={}",
                normalizedRole, scored.size(), topK, out.size(), threshold);
        return out;
    }

    @Override
    public void delete(List<String> idList) {
        for (String id : idList) {
            knowledgeDocumentRepository.deleteById(id);
            officerKnowledgeDocumentRepository.deleteById(id);
        }
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        throw new UnsupportedOperationException(
                "Filter-based delete is not supported by LocalMongoVectorStore; use delete(List<String> idList)");
    }

    private static Document toSpringDocument(KnowledgeDocument kd) {
        Map<String, Object> meta = new HashMap<>();
        if (kd.getCategory() != null) {
            meta.put(META_CATEGORY, kd.getCategory());
        }
        if (kd.getSource() != null) {
            meta.put(META_SOURCE, kd.getSource());
        }
        if (kd.getChunkIndex() != null) {
            meta.put(META_CHUNK_INDEX, kd.getChunkIndex());
        }
        if (kd.getSectionHeading() != null) {
            meta.put(META_SECTION_HEADING, kd.getSectionHeading());
        }
        if (kd.getAudienceRole() != null) {
            meta.put(META_AUDIENCE_ROLE, kd.getAudienceRole());
        }
        if (kd.getEmbeddingModel() != null) {
            meta.put(META_EMBEDDING_MODEL, kd.getEmbeddingModel());
        }
        if (kd.getEmbeddingVersion() != null) {
            meta.put(META_EMBEDDING_VERSION, kd.getEmbeddingVersion());
        }
        return new Document(kd.getId(), kd.getContent(), meta);
    }

    private static Document toSpringDocument(OfficerKnowledgeDocument kd) {
        Map<String, Object> meta = new HashMap<>();
        if (kd.getCategory() != null) {
            meta.put(META_CATEGORY, kd.getCategory());
        }
        if (kd.getSource() != null) {
            meta.put(META_SOURCE, kd.getSource());
        }
        if (kd.getChunkIndex() != null) {
            meta.put(META_CHUNK_INDEX, kd.getChunkIndex());
        }
        if (kd.getSectionHeading() != null) {
            meta.put(META_SECTION_HEADING, kd.getSectionHeading());
        }
        if (kd.getAudienceRole() != null) {
            meta.put(META_AUDIENCE_ROLE, kd.getAudienceRole());
        }
        if (kd.getEmbeddingModel() != null) {
            meta.put(META_EMBEDDING_MODEL, kd.getEmbeddingModel());
        }
        if (kd.getEmbeddingVersion() != null) {
            meta.put(META_EMBEDDING_VERSION, kd.getEmbeddingVersion());
        }
        return new Document(kd.getId(), kd.getContent(), meta);
    }

    private boolean isEmbeddingCompatible(KnowledgeDocument kd) {
        String docModel = normalize(kd.getEmbeddingModel());
        String docVersion = normalize(kd.getEmbeddingVersion());

        boolean modelMismatch = !docModel.isEmpty() && !activeEmbeddingModelTag.isEmpty() && !docModel.equals(activeEmbeddingModelTag);
        boolean versionMismatch = !docVersion.isEmpty() && !activeEmbeddingVersion.isEmpty() && !docVersion.equals(activeEmbeddingVersion);
        boolean mismatch = modelMismatch || versionMismatch;
        if (!mismatch) {
            return true;
        }

        if (strictEmbeddingCompatibility) {
            log.warn(
                    "Skipping incompatible embedding doc id={} (doc model/version={}/{}, active={}/{})",
                    kd.getId(), safe(docModel), safe(docVersion), safe(activeEmbeddingModelTag), safe(activeEmbeddingVersion));
            return false;
        }

        log.warn(
                "Embedding metadata mismatch for doc id={} (doc model/version={}/{}, active={}/{}). "
                        + "Allowed because strict mode is disabled.",
                kd.getId(), safe(docModel), safe(docVersion), safe(activeEmbeddingModelTag), safe(activeEmbeddingVersion));
        return true;
    }

    private boolean isEmbeddingCompatible(OfficerKnowledgeDocument kd) {
        String docModel = normalize(kd.getEmbeddingModel());
        String docVersion = normalize(kd.getEmbeddingVersion());

        boolean modelMismatch = !docModel.isEmpty() && !activeEmbeddingModelTag.isEmpty() && !docModel.equals(activeEmbeddingModelTag);
        boolean versionMismatch = !docVersion.isEmpty() && !activeEmbeddingVersion.isEmpty() && !docVersion.equals(activeEmbeddingVersion);
        boolean mismatch = modelMismatch || versionMismatch;
        if (!mismatch) {
            return true;
        }

        if (strictEmbeddingCompatibility) {
            log.warn(
                    "Skipping incompatible embedding doc id={} (doc model/version={}/{}, active={}/{})",
                    kd.getId(), safe(docModel), safe(docVersion), safe(activeEmbeddingModelTag), safe(activeEmbeddingVersion));
            return false;
        }

        log.warn(
                "Embedding metadata mismatch for doc id={} (doc model/version={}/{}, active={}/{}). "
                        + "Allowed because strict mode is disabled.",
                kd.getId(), safe(docModel), safe(docVersion), safe(activeEmbeddingModelTag), safe(activeEmbeddingVersion));
        return true;
    }

    private static OfficerKnowledgeDocument toOfficerEntity(KnowledgeDocument entity) {
        return OfficerKnowledgeDocument.builder()
                .id(entity.getId())
                .content(entity.getContent())
                .category(entity.getCategory())
                .source(entity.getSource())
                .audienceRole(entity.getAudienceRole())
                .chunkIndex(entity.getChunkIndex())
                .sectionHeading(entity.getSectionHeading())
                .embedding(entity.getEmbedding())
                .embeddingModel(entity.getEmbeddingModel())
                .embeddingVersion(entity.getEmbeddingVersion())
                .build();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim();
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "customer";
        }
        String r = role.trim().toLowerCase(Locale.ROOT);
        return "officer".equals(r) ? "officer" : "customer";
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static double cosineSimilarity(List<Float> v1, List<Float> v2) {
        double dot = 0.0, norm1 = 0.0, norm2 = 0.0;
        for (int i = 0; i < v1.size(); i++) {
            float a = v1.get(i);
            float b = v2.get(i);
            dot += a * b;
            norm1 += a * a;
            norm2 += b * b;
        }
        double d = Math.sqrt(norm1) * Math.sqrt(norm2);
        return d == 0.0 ? 0.0 : dot / d;
    }

    private static List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add(v);
        }
        return list;
    }

    private void addScoredCustomer(List<Scored> scored, KnowledgeDocument kd, List<Float> queryFloats) {
        if (!isEmbeddingCompatible(kd)) {
            return;
        }
        List<Float> emb = kd.getEmbedding();
        if (emb == null || emb.isEmpty() || emb.size() != queryFloats.size()) {
            return;
        }
        double sim = cosineSimilarity(queryFloats, emb);
        scored.add(new Scored(sim, toSpringDocument(kd)));
    }

    private void addScoredOfficer(List<Scored> scored, OfficerKnowledgeDocument kd, List<Float> queryFloats) {
        if (!isEmbeddingCompatible(kd)) {
            return;
        }
        List<Float> emb = kd.getEmbedding();
        if (emb == null || emb.isEmpty() || emb.size() != queryFloats.size()) {
            return;
        }
        double sim = cosineSimilarity(queryFloats, emb);
        scored.add(new Scored(sim, toSpringDocument(kd)));
    }

    private record Scored(double score, Document doc) {
    }
}
