package ai_chat;

import ai_chat.domain.KnowledgeDocument;
import ai_chat.repository.KnowledgeDocumentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.annotation.Order;

import java.util.Arrays;
import java.util.List;

@SpringBootApplication
@Order(1)
public class AiChatApplication implements CommandLineRunner {

    @Autowired
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    public static void main(String[] args) {
        SpringApplication.run(AiChatApplication.class, args);
    }

    @Override
    public void run(String... args) {

        if (knowledgeDocumentRepository.count() > 0) {
            return;
        }

        List<KnowledgeDocument> kbDocs = Arrays.asList(
                KnowledgeDocument.builder()
                        .content("Welcome to Bahrain Small Ship Registry Portal (SSRP). I can guide you with vessel registration. You can register a Boat, Jet Ski, or Dhow.")
                        .category("VesselRegistration")
                        .source("newVesselRegistration")
                        .build(),

                KnowledgeDocument.builder()
                        .content("For new vessel registration, you typically need proof of ownership, vessel details, and owner identification documents. This applies to all vessels: Boat, Jet Ski, and Dhow.")
                        .category("VesselRegistration")
                        .source("vessel-reg-documents")
                        .build(),

                KnowledgeDocument.builder()
                        .content("Boat registration documents include:\n" +
                                "- Owner ID (CPR for residents or passport for non-residents)\n" +
                                "- Proof of ownership (invoice or sale contract)\n" +
                                "- Boat specifications (length, hull material, engine details)\n" +
                                "- Hull Identification Number (HIN) or serial numbers if available\n" +
                                "- Photos of the boat\n" +
                                "- Import/customs documents if the boat is imported")
                        .category("BoatRegistration")
                        .source("vessel-reg-documents-boat")
                        .build(),

                KnowledgeDocument.builder()
                        .content("Jet Ski registration documents include:\n" +
                                "- Owner ID\n" +
                                "- Proof of ownership\n" +
                                "- Jet Ski make, model, and serial number\n" +
                                "- Engine details if applicable\n" +
                                "- Photos of the Jet Ski\n" +
                                "- Import/customs documents if applicable")
                        .category("JetSkiRegistration")
                        .source("vessel-reg-documents-jet-ski")
                        .build(),

                KnowledgeDocument.builder()
                        .content("Dhow registration documents include:\n" +
                                "- Owner ID\n" +
                                "- Proof of ownership\n" +
                                "- Dhow specifications (length, build details)\n" +
                                "- Serial numbers or identification if available\n" +
                                "- Photos of the dhow\n" +
                                "- Import/customs documents if applicable")
                        .category("DhowRegistration")
                        .source("vessel-reg-documents-dhow")
                        .build(),

                KnowledgeDocument.builder()
                        .content("Steps to register a Boat via SSRP portal:\n" +
                                "1) Go to New Vessel Registration → Boat\n" +
                                "2) Enter owner details and boat specifications\n" +
                                "3) Upload all required documents\n" +
                                "4) Submit the application\n" +
                                "5) Pay registration fees as displayed in the portal\n" +
                                "6) Wait for inspection/verification if required\n" +
                                "7) Receive registration certificate")
                        .category("BoatRegistration")
                        .source("vessel-reg-process-boat")
                        .build(),

                KnowledgeDocument.builder()
                        .content("Steps to register a Jet Ski via SSRP portal:\n" +
                                "1) Go to New Vessel Registration → Jet Ski\n" +
                                "2) Enter owner and Jet Ski details\n" +
                                "3) Upload required documents\n" +
                                "4) Submit the application\n" +
                                "5) Pay fees\n" +
                                "6) Wait for verification if needed\n" +
                                "7) Receive registration certificate")
                        .category("JetSkiRegistration")
                        .source("vessel-reg-process-jet-ski")
                        .build(),

                KnowledgeDocument.builder()
                        .content("Steps to register a Dhow via SSRP portal:\n" +
                                "1) Go to New Vessel Registration → Dhow\n" +
                                "2) Enter owner and dhow specifications\n" +
                                "3) Upload required documents\n" +
                                "4) Submit application\n" +
                                "5) Pay fees\n" +
                                "6) Wait for verification if applicable\n" +
                                "7) Receive registration certificate")
                        .category("DhowRegistration")
                        .source("vessel-reg-process-dhow")
                        .build(),

                KnowledgeDocument.builder()
                        .content("General vessel registration process in Bahrain SSRP:\n" +
                                "- Choose vessel type (Boat, Jet Ski, Dhow)\n" +
                                "- Enter owner and vessel details\n" +
                                "- Upload all required documents\n" +
                                "- Submit the application\n" +
                                "- Pay fees\n" +
                                "- Inspection/verification if required\n" +
                                "- Receive registration certificate")
                        .category("VesselRegistration")
                        .source("vessel-reg-process")
                        .build()
        );

        knowledgeDocumentRepository.saveAll(kbDocs);
        System.out.println("✅ KB documents inserted (without embeddings)");
    }
}
