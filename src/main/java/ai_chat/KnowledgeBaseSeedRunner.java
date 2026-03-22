package ai_chat;

import ai_chat.repository.KnowledgeDocumentRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/** Seeds the KB when empty; not registered for {@code test} profile. */
@Component
@Profile("!test")
@Order(1)
public class KnowledgeBaseSeedRunner implements CommandLineRunner {

    @Autowired
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Autowired
    private VectorStore vectorStore;

    @Override
    public void run(String... args) {

        if (knowledgeDocumentRepository.count() > 0) {
            return;
        }

        List<Document> seeds = Arrays.asList(
                kb("Welcome to Bahrain Small Ship Registry Portal (SSRP). I can guide you with vessel registration. You can register a Boat, Jet Ski, or Dhow.",
                        "VesselRegistration", "newVesselRegistration"),

                kb("For new vessel registration, you typically need proof of ownership, vessel details, and owner identification documents. This applies to all vessels: Boat, Jet Ski, and Dhow.",
                        "VesselRegistration", "vessel-reg-documents"),

                kb("Boat registration documents include:\n" +
                                "- Owner ID (CPR for residents or passport for non-residents)\n" +
                                "- Proof of ownership (invoice or sale contract)\n" +
                                "- Boat specifications (length, hull material, engine details)\n" +
                                "- Hull Identification Number (HIN) or serial numbers if available\n" +
                                "- Photos of the boat\n" +
                                "- Import/customs documents if the boat is imported",
                        "BoatRegistration", "vessel-reg-documents-boat"),

                kb("Jet Ski registration documents include:\n" +
                                "- Owner ID\n" +
                                "- Proof of ownership\n" +
                                "- Jet Ski make, model, and serial number\n" +
                                "- Engine details if applicable\n" +
                                "- Photos of the Jet Ski\n" +
                                "- Import/customs documents if applicable",
                        "JetSkiRegistration", "vessel-reg-documents-jet-ski"),

                kb("Dhow registration documents include:\n" +
                                "- Owner ID\n" +
                                "- Proof of ownership\n" +
                                "- Dhow specifications (length, build details)\n" +
                                "- Serial numbers or identification if available\n" +
                                "- Photos of the dhow\n" +
                                "- Import/customs documents if applicable",
                        "DhowRegistration", "vessel-reg-documents-dhow"),

                kb("Steps to register a Boat via SSRP portal:\n" +
                                "1) Go to New Vessel Registration → Boat\n" +
                                "2) Enter owner details and boat specifications\n" +
                                "3) Upload all required documents\n" +
                                "4) Submit the application\n" +
                                "5) Pay registration fees as displayed in the portal\n" +
                                "6) Wait for inspection/verification if required\n" +
                                "7) Receive registration certificate",
                        "BoatRegistration", "vessel-reg-process-boat"),

                kb("Steps to register a Jet Ski via SSRP portal:\n" +
                                "1) Go to New Vessel Registration → Jet Ski\n" +
                                "2) Enter owner and Jet Ski details\n" +
                                "3) Upload required documents\n" +
                                "4) Submit the application\n" +
                                "5) Pay fees\n" +
                                "6) Wait for verification if needed\n" +
                                "7) Receive registration certificate",
                        "JetSkiRegistration", "vessel-reg-process-jet-ski"),

                kb("Steps to register a Dhow via SSRP portal:\n" +
                                "1) Go to New Vessel Registration → Dhow\n" +
                                "2) Enter owner and dhow specifications\n" +
                                "3) Upload required documents\n" +
                                "4) Submit application\n" +
                                "5) Pay fees\n" +
                                "6) Wait for verification if applicable\n" +
                                "7) Receive registration certificate",
                        "DhowRegistration", "vessel-reg-process-dhow"),

                kb("General vessel registration process in Bahrain SSRP:\n" +
                                "- Choose vessel type (Boat, Jet Ski, Dhow)\n" +
                                "- Enter owner and vessel details\n" +
                                "- Upload all required documents\n" +
                                "- Submit the application\n" +
                                "- Pay fees\n" +
                                "- Inspection/verification if required\n" +
                                "- Receive registration certificate",
                        "VesselRegistration", "vessel-reg-process")
        );

        vectorStore.add(seeds);
        System.out.println("✅ KB documents inserted with embeddings (VectorStore.add)");
    }

    private static Document kb(String text, String category, String source) {
        return Document.builder()
                .text(text)
                .metadata("category", category)
                .metadata("source", source)
                .build();
    }
}
