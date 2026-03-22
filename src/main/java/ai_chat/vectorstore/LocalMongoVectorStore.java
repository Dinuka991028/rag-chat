package ai_chat.vectorstore;

import ai_chat.domain.KnowledgeDocument;
import ai_chat.repository.KnowledgeDocumentRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
/**
 * Spring AI {@link VectorStore} backed by self-hosted MongoDB ({@link KnowledgeDocument} rows).
 * Uses cosine similarity in-process (no Atlas Vector Search).
 */
public class LocalMongoVectorStore implements VectorStore {

    private static final String META_CATEGORY = "category";
    private static final String META_SOURCE = "source";
    private static final String META_CHUNK_INDEX = "chunkIndex";
    private static final String META_SECTION_HEADING = "sectionHeading";

    private final EmbeddingModel embeddingModel;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;

    public LocalMongoVectorStore(EmbeddingModel embeddingModel,
                                 KnowledgeDocumentRepository knowledgeDocumentRepository) {
        this.embeddingModel = embeddingModel;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
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
            knowledgeDocumentRepository.save(entity);
        }
    }

    @Nullable
    @Override
    public List<Document> similaritySearch(SearchRequest request) {
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

        List<KnowledgeDocument> all = knowledgeDocumentRepository.findAll();
        List<Scored> scored = new ArrayList<>();
        for (KnowledgeDocument kd : all) {
            List<Float> emb = kd.getEmbedding();
            if (emb == null || emb.isEmpty()) {
                continue;
            }
            if (emb.size() != queryFloats.size()) {
                continue;
            }
            double sim = cosineSimilarity(queryFloats, emb);
            scored.add(new Scored(sim, kd));
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
            out.add(toSpringDocument(s.kd));
            if (out.size() >= topK) {
                break;
            }
        }
        return out;
    }

    @Override
    public void delete(List<String> idList) {
        for (String id : idList) {
            knowledgeDocumentRepository.deleteById(id);
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
        return new Document(kd.getId(), kd.getContent(), meta);
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

    private record Scored(double score, KnowledgeDocument kd) {
    }
}
