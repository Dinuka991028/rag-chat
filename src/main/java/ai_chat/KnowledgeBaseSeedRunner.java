package ai_chat;

import ai_chat.kb.PdfTextExtractor;
import ai_chat.kb.SrsChunker;
import ai_chat.kb.SrsMarkdownChunker;
import ai_chat.kb.SrsTextPreprocessor;
import ai_chat.repository.KnowledgeDocumentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Seeds the KB when empty: curated customer FAQ snippets (high precision) plus optional SRS PDF chunks.
 * Not registered for {@code test} profile.
 */
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

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${conf.kb.json-enabled:true}")
    private boolean kbJsonEnabled;

    @Value("${conf.kb.json-vessel-services-classpath:classpath:kb/vessel-registration-3-services-kb-text-category-source.json}")
    private String vesselRegistrationServicesJsonClasspath;

    @Value("${conf.kb.json-validations-classpath:classpath:kb/vessel-registration-validations-kb-format.json}")
    private String vesselRegistrationValidationsJsonClasspath;

    @Value("${conf.kb.json-officer-enabled:true}")
    private boolean officerKbJsonEnabled;

    @Value("${conf.kb.json-officer-classpath:classpath:kb/officer_kb.json}")
    private String officerKbJsonClasspath;

    @Value("${conf.kb.srs-pdf-enabled:true}")
    private boolean srsPdfEnabled;

    @Value("${conf.kb.srs-pdf-classpath:classpath:kb/ssrp-srs.pdf}")
    private String srsPdfClasspath;

    /** When true, use {@link #srsMarkdownClasspath} if the resource exists (LLM-friendly markdown); else PDF. */
    @Value("${conf.kb.srs-use-markdown:true}")
    private boolean srsUseMarkdown;

    @Value("${conf.kb.srs-markdown-classpath:classpath:kb/ssrp-srs-llm.md}")
    private String srsMarkdownClasspath;

    @Value("${conf.kb.srs-chunk-max-chars:900}")
    private int srsChunkMaxChars;

    @Value("${conf.kb.srs-chunk-overlap:200}")
    private int srsChunkOverlap;

    @Override
    public void run(String... args) {

        if (knowledgeDocumentRepository.count() > 0) {
            return;
        }

        List<Document> seeds = new ArrayList<>();
        seeds.addAll(curatedFaqDocuments());
        seeds.addAll(loadKbJsonDocuments());
        seeds.addAll(loadSrsKnowledgeDocuments());

        if (seeds.isEmpty()) {
            log.warn(
                    "KB not seeded: add curated FAQ in code and/or place kb JSON and/or ssrp-srs.pdf / ssrp-srs-llm.md under src/main/resources/kb/.");
            return;
        }

        vectorStore.add(seeds);
        log.info("KB seeded with {} document(s) (curated FAQ + KB JSON + SRS) (VectorStore.add)", seeds.size());
    }

    /** Short, customer-tested answers; category is not {@code SRS} so retrieval can prefer these over raw PDF chunks. */
    private static List<Document> curatedFaqDocuments() {
        return Arrays.asList(
                kb("Welcome to Bahrain Small Ship Registry Portal (SSRP). I can guide you with vessel registration. You can register a Boat, Jet Ski, or Dhow.",
                        "VesselRegistration", "curated-faq-newVesselRegistration"),

                kb("For new vessel registration, you typically need proof of ownership, vessel details, and owner identification documents. This applies to all vessels: Boat, Jet Ski, and Dhow.",
                        "VesselRegistration", "curated-faq-vessel-reg-documents"),

                kb("Boat registration documents include:\n"
                                + "- Owner ID (CPR for residents or passport for non-residents)\n"
                                + "- Proof of ownership (invoice or sale contract)\n"
                                + "- Boat specifications (length, hull material, engine details)\n"
                                + "- Hull Identification Number (HIN) or serial numbers if available\n"
                                + "- Photos of the boat\n"
                                + "- Import/customs documents if the boat is imported",
                        "BoatRegistration", "curated-faq-vessel-reg-documents-boat"),

                kb("Jet Ski registration documents include:\n"
                                + "- Owner ID\n"
                                + "- Proof of ownership\n"
                                + "- Jet Ski make, model, and serial number\n"
                                + "- Engine details if applicable\n"
                                + "- Photos of the Jet Ski\n"
                                + "- Import/customs documents if applicable",
                        "JetSkiRegistration", "curated-faq-vessel-reg-documents-jet-ski"),

                kb("Dhow registration documents include:\n"
                                + "- Owner ID\n"
                                + "- Proof of ownership\n"
                                + "- Dhow specifications (length, build details)\n"
                                + "- Serial numbers or identification if available\n"
                                + "- Photos of the dhow\n"
                                + "- Import/customs documents if applicable",
                        "DhowRegistration", "curated-faq-vessel-reg-documents-dhow"),

                kb("Steps to register a Boat via SSRP portal:\n"
                                + "1) Go to New Vessel Registration → Boat\n"
                                + "2) Enter owner details and boat specifications\n"
                                + "3) Upload all required documents\n"
                                + "4) Submit the application\n"
                                + "5) Pay registration fees as displayed in the portal\n"
                                + "6) Wait for inspection/verification if required\n"
                                + "7) Receive registration certificate",
                        "BoatRegistration", "curated-faq-vessel-reg-process-boat"),

                kb("Steps to register a Jet Ski via SSRP portal:\n"
                                + "1) Go to New Vessel Registration → Jet Ski\n"
                                + "2) Enter owner and Jet Ski details\n"
                                + "3) Upload required documents\n"
                                + "4) Submit the application\n"
                                + "5) Pay fees\n"
                                + "6) Wait for verification if needed\n"
                                + "7) Receive registration certificate",
                        "JetSkiRegistration", "curated-faq-vessel-reg-process-jet-ski"),

                kb("Steps to register a Dhow via SSRP portal:\n"
                                + "1) Go to New Vessel Registration → Dhow\n"
                                + "2) Enter owner and dhow specifications\n"
                                + "3) Upload required documents\n"
                                + "4) Submit application\n"
                                + "5) Pay fees\n"
                                + "6) Wait for verification if applicable\n"
                                + "7) Receive registration certificate",
                        "DhowRegistration", "curated-faq-vessel-reg-process-dhow"),

                kb("General vessel registration process in Bahrain SSRP:\n"
                                + "- Choose vessel type (Boat, Jet Ski, Dhow)\n"
                                + "- Enter owner and vessel details\n"
                                + "- Upload all required documents\n"
                                + "- Submit the application\n"
                                + "- Pay fees\n"
                                + "- Inspection/verification if required\n"
                                + "- Receive registration certificate",
                        "VesselRegistration", "curated-faq-vessel-reg-process")
        );
    }

    private static Document kb(String text, String category, String source) {
        return Document.builder()
                .text(text)
                .metadata("category", category)
                .metadata("source", source)
                .build();
    }

    private List<Document> loadKbJsonDocuments() {
        if (!kbJsonEnabled) {
            return List.of();
        }

        List<Document> out = new ArrayList<>();
        out.addAll(loadKbJsonArrayDocuments(vesselRegistrationServicesJsonClasspath, "vessel-registration-3-services-kb-text-category-source.json"));
        out.addAll(loadKbJsonArrayDocuments(vesselRegistrationValidationsJsonClasspath, "vessel-registration-validations-kb-format.json"));
        out.addAll(loadOfficerKbJsonDocuments());
        return out;
    }

    private List<Document> loadOfficerKbJsonDocuments() {
        if (!officerKbJsonEnabled) {
            return List.of();
        }
        Resource resource = resourceLoader.getResource(officerKbJsonClasspath);
        if (!resource.exists() || !resource.isReadable()) {
            log.info("Officer KB JSON not found at {} — skipping", officerKbJsonClasspath);
            return List.of();
        }
        List<Document> docs = new ArrayList<>();
        try (InputStream in = resource.getInputStream();
             JsonParser parser = objectMapper.getFactory().createParser(in)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                log.warn("Officer KB JSON at {} is not an array; skipping", officerKbJsonClasspath);
                return List.of();
            }
            int idx = 0;
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                JsonNode row = objectMapper.readTree(parser);
                String text = buildOfficerSummaryText(row);
                if (text == null || text.isBlank()) {
                    continue;
                }
                Document doc = Document.builder()
                        .text(text)
                        .metadata("category", "OfficerJobSummary")
                        .metadata("source", "officer-kb-json")
                        .metadata("audienceRole", "officer")
                        .metadata("chunkIndex", idx++)
                        .build();
                docs.add(doc);
            }
            log.info("Indexed Officer KB JSON: {} document(s) from {}", docs.size(), officerKbJsonClasspath);
            return docs;
        } catch (IOException e) {
            log.warn("Could not read Officer KB JSON at {}: {}", officerKbJsonClasspath, e.getMessage());
            return List.of();
        }
    }

    private static String buildOfficerSummaryText(JsonNode row) {
        if (row == null || row.isMissingNode() || row.isNull()) {
            return "";
        }
        String jobId = extractFlexibleString(row.get("jobId"));
        String taskId = extractFlexibleString(row.get("taskId"));
        String taskName = extractFlexibleString(row.get("taskName"));
        String serviceId = extractFlexibleString(row.get("serviceId"));
        String serviceName = extractFlexibleString(row.get("serviceName"));
        String shipNumber = extractFlexibleString(row.get("shipNumber"));
        String shipName = extractFlexibleString(row.get("shipName"));
        String vesselType = extractFlexibleString(row.get("vesselType"));
        String customerId = extractFlexibleString(row.get("customerId"));
        String customerName = extractFlexibleString(row.get("customerName"));
        String serialNumber = extractFlexibleString(row.get("serialNumber"));
        String submitDate = extractFlexibleString(row.get("submitDate"));
        String completedDate = extractFlexibleString(row.get("completedDate"));
        String taskStatus = extractFlexibleString(row.get("taskStatus"));
        String processInstanceId = extractFlexibleString(row.get("processInstanceId"));
        String taskAction = extractFlexibleString(row.get("taskAction"));

        StringBuilder sb = new StringBuilder();
        appendLine(sb, "Officer case summary");
        appendLine(sb, "Job ID", jobId);
        appendLine(sb, "Task ID", taskId);
        appendLine(sb, "Task Name", taskName);
        appendLine(sb, "Service ID", serviceId);
        appendLine(sb, "Service Name", serviceName);
        appendLine(sb, "Ship Number", shipNumber);
        appendLine(sb, "Ship Name", shipName);
        appendLine(sb, "Vessel Type", vesselType);
        appendLine(sb, "Customer ID", customerId);
        appendLine(sb, "Customer Name", customerName);
        appendLine(sb, "Serial Number", serialNumber);
        appendLine(sb, "Submit Date", submitDate);
        appendLine(sb, "Completed Date", completedDate);
        appendLine(sb, "Task Status", taskStatus);
        appendLine(sb, "Process Instance ID", processInstanceId);
        appendLine(sb, "Task Action", taskAction);
        return sb.toString().trim();
    }

    private static String extractFlexibleString(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return "";
        }
        if (n.isTextual() || n.isNumber() || n.isBoolean()) {
            return n.asText();
        }
        if (n.isObject()) {
            JsonNode oid = n.get("$oid");
            if (oid != null && !oid.isNull()) {
                return oid.asText("");
            }
            JsonNode numberLong = n.get("$numberLong");
            if (numberLong != null && !numberLong.isNull()) {
                return numberLong.asText("");
            }
        }
        return "";
    }

    private static void appendLine(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append(": ").append(value.trim()).append('\n');
        }
    }

    private static void appendLine(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(value.trim()).append('\n');
        }
    }

    private List<Document> loadKbJsonArrayDocuments(String jsonClasspath, String humanName) {
        Resource resource = resourceLoader.getResource(jsonClasspath);
        if (!resource.exists() || !resource.isReadable()) {
            log.info("KB JSON not found at {} — skipping ({})", jsonClasspath, humanName);
            return List.of();
        }
        try (InputStream in = resource.getInputStream()) {
            List<KbTextCategorySourceItem> items =
                    objectMapper.readValue(in, new TypeReference<List<KbTextCategorySourceItem>>() {
                    });
            if (items == null || items.isEmpty()) {
                log.info("KB JSON {} had no items — skipping", humanName);
                return List.of();
            }

            List<Document> docs = new ArrayList<>(items.size());
            for (KbTextCategorySourceItem it : items) {
                if (it == null || it.text() == null || it.text().isBlank()) {
                    continue;
                }
                docs.add(Document.builder()
                        .text(it.text())
                        .metadata("category", it.category())
                        .metadata("source", it.source())
                        .build());
            }

            log.info("Indexed KB JSON: {} document(s) from {}", docs.size(), humanName);
            return docs;
        } catch (IOException e) {
            log.warn("Could not read KB JSON at {} ({}): {}", jsonClasspath, humanName, e.getMessage());
            return List.of();
        }
    }

    private record KbTextCategorySourceItem(String text, String category, String source) {
    }

    private List<Document> loadSrsKnowledgeDocuments() {
        if (srsUseMarkdown) {
            Resource md = resourceLoader.getResource(srsMarkdownClasspath);
            if (md.exists() && md.isReadable()) {
                try (InputStream in = md.getInputStream()) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    List<SrsChunker.SrsIndexedChunk> chunks =
                            SrsMarkdownChunker.chunk(text, srsChunkMaxChars, srsChunkOverlap);
                    if (!chunks.isEmpty()) {
                        log.info("Using LLM-friendly markdown SRS at {} ({} chunks)", srsMarkdownClasspath, chunks.size());
                        return toSrsDocuments(chunks, "ssrp-srs-llm-md");
                    }
                } catch (IOException e) {
                    log.warn("Could not read SRS markdown at {}: {}", srsMarkdownClasspath, e.getMessage());
                }
            } else {
                log.info("SRS markdown not found at {} — falling back to PDF if enabled.", srsMarkdownClasspath);
            }
        }
        return loadSrsPdfDocuments();
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
            log.info("Indexed SRS PDF: {} chunk(s) from {} (cleanup + chunking)", chunks.size(), srsPdfClasspath);
            return toSrsDocuments(chunks, "ssrp-srs-pdf");
        } catch (IOException e) {
            log.warn("Could not read SRS PDF at {}: {}", srsPdfClasspath, e.getMessage());
            return List.of();
        }
    }

    private List<Document> toSrsDocuments(List<SrsChunker.SrsIndexedChunk> chunks, String sourceId) {
        List<Document> docs = new ArrayList<>(chunks.size());
        for (SrsChunker.SrsIndexedChunk ch : chunks) {
            var b = Document.builder()
                    .text(ch.textForEmbedding())
                    .metadata("category", "SRS")
                    .metadata("source", sourceId)
                    .metadata("chunkIndex", ch.chunkIndex());
            if (ch.sectionHeading() != null && !ch.sectionHeading().isBlank()) {
                b.metadata("sectionHeading", ch.sectionHeading());
            }
            docs.add(b.build());
        }
        return docs;
    }
}
