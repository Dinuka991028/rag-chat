package ai_chat.service;

import ai_chat.dto.AddKnowledgeRequest;
import ai_chat.kb.PdfTextExtractor;
import ai_chat.kb.SrsChunker;
import ai_chat.kb.SrsTextPreprocessor;
import ai_chat.repository.KbTrainingDraftRepository;
import ai_chat.repository.KnowledgeDocumentRepository;
import ai_chat.repository.UnknownQueryRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeAdminService {

    private final VectorStore vectorStore;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final UnknownQueryRepository unknownQueryRepository;
    private final KbTrainingDraftRepository kbTrainingDraftRepository;

    @Value("${conf.kb.srs-chunk-max-chars:900}")
    private int srsChunkMaxChars;

    @Value("${conf.kb.srs-chunk-overlap:200}")
    private int srsChunkOverlap;

    public KnowledgeAdminService(
            VectorStore vectorStore,
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            UnknownQueryRepository unknownQueryRepository,
            KbTrainingDraftRepository kbTrainingDraftRepository) {
        this.vectorStore = vectorStore;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.unknownQueryRepository = unknownQueryRepository;
        this.kbTrainingDraftRepository = kbTrainingDraftRepository;
    }

    /** One KB article; optional title is prepended so embeddings match customer phrasing better. */
    public long addTextEntry(AddKnowledgeRequest request) {
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
        String body = request.getContent().trim();
        if (StringUtils.hasText(request.getTitle())) {
            body = request.getTitle().trim() + "\n\n" + body;
        }
        String category = StringUtils.hasText(request.getCategory()) ? request.getCategory().trim() : "Admin";
        String source = StringUtils.hasText(request.getSource()) ? request.getSource().trim() : "admin";

        Document doc = Document.builder()
                .text(body)
                .metadata("category", category)
                .metadata("source", source)
                .build();
        vectorStore.add(List.of(doc));
        return knowledgeDocumentRepository.count();
    }

    /**
     * Full-document import: same cleanup + section chunking as startup PDF seeding.
     * Use for organized manuals (category e.g. SRS, Policy).
     */
    public int importPdf(MultipartFile file, String category, String sourcePrefix) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }
        String cat = StringUtils.hasText(category) ? category.trim() : "AdminImport";
        String baseName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        String safeName = baseName.replaceAll("[^a-zA-Z0-9._-]", "_");
        String source = (StringUtils.hasText(sourcePrefix) ? sourcePrefix.trim() + "-" : "admin-pdf-") + safeName;

        String raw = PdfTextExtractor.extractText(file.getInputStream());
        String cleaned = SrsTextPreprocessor.cleanForChunking(raw);
        List<SrsChunker.SrsIndexedChunk> chunks =
                SrsChunker.chunk(cleaned, srsChunkMaxChars, srsChunkOverlap);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("No text extracted from PDF (empty or image-only?)");
        }
        List<Document> docs = new ArrayList<>(chunks.size());
        for (SrsChunker.SrsIndexedChunk ch : chunks) {
            var b = Document.builder()
                    .text(ch.textForEmbedding())
                    .metadata("category", cat)
                    .metadata("source", source)
                    .metadata("chunkIndex", ch.chunkIndex());
            if (ch.sectionHeading() != null && !ch.sectionHeading().isBlank()) {
                b.metadata("sectionHeading", ch.sectionHeading());
            }
            docs.add(b.build());
        }
        vectorStore.add(docs);
        return chunks.size();
    }

    public Page<ai_chat.domain.UnknownQuery> listUnknownQueries(Pageable pageable) {
        return unknownQueryRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    public Page<ai_chat.domain.UnknownQuery> listUnknownQueriesByRole(String role, Pageable pageable) {
        if (role == null || role.isBlank() || "all".equalsIgnoreCase(role.trim())) {
            return listUnknownQueries(pageable);
        }
        String normalizedRole = normalizeRole(role);
        return unknownQueryRepository.findByRoleOrderByCreatedAtDesc(normalizedRole, pageable);
    }

    public void deleteUnknownQuery(String id) {
        unknownQueryRepository.deleteById(id);
    }

    /**
     * Clears all operational RAG/training data so the system can be re-seeded cleanly.
     */
    public Map<String, Long> clearAllOperationalData() {
        long kbBefore = knowledgeDocumentRepository.count();
        long unknownBefore = unknownQueryRepository.count();
        long draftsBefore = kbTrainingDraftRepository.count();

        kbTrainingDraftRepository.deleteAll();
        unknownQueryRepository.deleteAll();
        knowledgeDocumentRepository.deleteAll();

        Map<String, Long> out = new LinkedHashMap<>();
        out.put("deletedKbDocuments", kbBefore);
        out.put("deletedUnknownQueries", unknownBefore);
        out.put("deletedTrainingDrafts", draftsBefore);
        out.put("remainingKbDocuments", knowledgeDocumentRepository.count());
        out.put("remainingUnknownQueries", unknownQueryRepository.count());
        out.put("remainingTrainingDrafts", kbTrainingDraftRepository.count());
        return out;
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "customer";
        }
        String r = role.trim().toLowerCase();
        return "officer".equals(r) ? "officer" : "customer";
    }
}
