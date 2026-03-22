package ai_chat;

import ai_chat.kb.PdfTextExtractor;
import ai_chat.kb.SrsChunker;
import ai_chat.kb.SrsTextPreprocessor;
import ai_chat.repository.KnowledgeDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Seeds the KB from the SRS PDF only when empty; not registered for {@code test} profile. */
@Component
@Profile("!test")
@Order(1)
public class KnowledgeBaseSeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseSeedRunner.class);

    @Autowired
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private ResourceLoader resourceLoader;

    @Value("${conf.kb.srs-pdf-enabled:true}")
    private boolean srsPdfEnabled;

    @Value("${conf.kb.srs-pdf-classpath:classpath:kb/ssrp-srs.pdf}")
    private String srsPdfClasspath;

    @Value("${conf.kb.srs-chunk-max-chars:900}")
    private int srsChunkMaxChars;

    @Value("${conf.kb.srs-chunk-overlap:200}")
    private int srsChunkOverlap;

    @Override
    public void run(String... args) {

        if (knowledgeDocumentRepository.count() > 0) {
            return;
        }

        List<Document> seeds = loadSrsPdfDocuments();
        if (seeds.isEmpty()) {
            log.warn("KB not seeded: no SRS chunks (place ssrp-srs.pdf under src/main/resources/kb/, "
                    + "set conf.kb.srs-pdf-enabled=true, or fix PDF text extraction). RAG will have no documents.");
            return;
        }

        vectorStore.add(seeds);
        log.info("KB seeded with {} SRS chunk(s) (VectorStore.add)", seeds.size());
    }

    private List<Document> loadSrsPdfDocuments() {
        if (!srsPdfEnabled) {
            return List.of();
        }
        Resource resource = resourceLoader.getResource(srsPdfClasspath);
        if (!resource.exists() || !resource.isReadable()) {
            log.info("SRS PDF not found at {} — skipping PDF indexing (add ssrp-srs.pdf under src/main/resources/kb/ to enable).",
                    srsPdfClasspath);
            return List.of();
        }
        try (InputStream in = resource.getInputStream()) {
            String raw = PdfTextExtractor.extractText(in);
            String cleaned = SrsTextPreprocessor.cleanForChunking(raw);
            List<SrsChunker.SrsIndexedChunk> chunks =
                    SrsChunker.chunk(cleaned, srsChunkMaxChars, srsChunkOverlap);
            if (chunks.isEmpty()) {
                log.warn("SRS PDF at {} produced no text (empty or scanned image PDF?).", srsPdfClasspath);
                return List.of();
            }
            List<Document> docs = new ArrayList<>(chunks.size());
            for (SrsChunker.SrsIndexedChunk ch : chunks) {
                var b = Document.builder()
                        .text(ch.textForEmbedding())
                        .metadata("category", "SRS")
                        .metadata("source", "ssrp-srs-pdf")
                        .metadata("chunkIndex", ch.chunkIndex());
                if (ch.sectionHeading() != null && !ch.sectionHeading().isBlank()) {
                    b.metadata("sectionHeading", ch.sectionHeading());
                }
                docs.add(b.build());
            }
            log.info("Indexed SRS PDF: {} chunk(s) from {} (after cleanup + section-aware chunking)", chunks.size(),
                    srsPdfClasspath);
            return docs;
        } catch (IOException e) {
            log.warn("Could not read SRS PDF at {}: {}", srsPdfClasspath, e.getMessage());
            return List.of();
        }
    }
}
