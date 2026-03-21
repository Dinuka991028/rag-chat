package ai_chat;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Arrays;
import java.util.List;

@SpringBootApplication
public class AiChatApplication implements CommandLineRunner {

    @Autowired
    private MongoTemplate mongoTemplate;

    public static void main(String[] args) {
        SpringApplication.run(AiChatApplication.class, args);
    }

    @Override
    public void run(String... args) {

        String collectionName = "kb_documents";

        if (!mongoTemplate.collectionExists(collectionName)) {
            mongoTemplate.createCollection(collectionName);
        }

        if (mongoTemplate.getCollection(collectionName).countDocuments() == 0) {

            List<Document> kbDocs = Arrays.asList(

                    new Document("content",
                            "Welcome to Bahrain Small Ship Registry Portal (SSRP). I can guide you with vessel registration. You can register a Boat, Jet Ski, or Dhow.")
                            .append("category", "VesselRegistration")
                            .append("source", "newVesselRegistration"),

                    new Document("content",
                            "For new vessel registration, you typically need proof of ownership, vessel details, and owner identification documents. This applies to all vessels: Boat, Jet Ski, and Dhow.")
                            .append("category", "VesselRegistration")
                            .append("source", "vessel-reg-documents"),

                    new Document("content",
                            "Boat registration documents include:\n" +
                                    "- Owner ID (CPR for residents or passport for non-residents)\n" +
                                    "- Proof of ownership (invoice or sale contract)\n" +
                                    "- Boat specifications (length, hull material, engine details)\n" +
                                    "- Hull Identification Number (HIN) or serial numbers if available\n" +
                                    "- Photos of the boat\n" +
                                    "- Import/customs documents if the boat is imported")
                            .append("category", "BoatRegistration")
                            .append("source", "vessel-reg-documents-boat"),

                    new Document("content",
                            "Jet Ski registration documents include:\n" +
                                    "- Owner ID\n" +
                                    "- Proof of ownership\n" +
                                    "- Jet Ski make, model, and serial number\n" +
                                    "- Engine details if applicable\n" +
                                    "- Photos of the Jet Ski\n" +
                                    "- Import/customs documents if applicable")
                            .append("category", "JetSkiRegistration")
                            .append("source", "vessel-reg-documents-jet-ski"),

                    new Document("content",
                            "Dhow registration documents include:\n" +
                                    "- Owner ID\n" +
                                    "- Proof of ownership\n" +
                                    "- Dhow specifications (length, build details)\n" +
                                    "- Serial numbers or identification if available\n" +
                                    "- Photos of the dhow\n" +
                                    "- Import/customs documents if applicable")
                            .append("category", "DhowRegistration")
                            .append("source", "vessel-reg-documents-dhow"),

                    new Document("content",
                            "Steps to register a Boat via SSRP portal:\n" +
                                    "1) Go to New Vessel Registration → Boat\n" +
                                    "2) Enter owner details and boat specifications\n" +
                                    "3) Upload all required documents\n" +
                                    "4) Submit the application\n" +
                                    "5) Pay registration fees as displayed in the portal\n" +
                                    "6) Wait for inspection/verification if required\n" +
                                    "7) Receive registration certificate")
                            .append("category", "BoatRegistration")
                            .append("source", "vessel-reg-process-boat"),

                    new Document("content",
                            "Steps to register a Jet Ski via SSRP portal:\n" +
                                    "1) Go to New Vessel Registration → Jet Ski\n" +
                                    "2) Enter owner and Jet Ski details\n" +
                                    "3) Upload required documents\n" +
                                    "4) Submit the application\n" +
                                    "5) Pay fees\n" +
                                    "6) Wait for verification if needed\n" +
                                    "7) Receive registration certificate")
                            .append("category", "JetSkiRegistration")
                            .append("source", "vessel-reg-process-jet-ski"),

                    new Document("content",
                            "Steps to register a Dhow via SSRP portal:\n" +
                                    "1) Go to New Vessel Registration → Dhow\n" +
                                    "2) Enter owner and dhow specifications\n" +
                                    "3) Upload required documents\n" +
                                    "4) Submit application\n" +
                                    "5) Pay fees\n" +
                                    "6) Wait for verification if applicable\n" +
                                    "7) Receive registration certificate")
                            .append("category", "DhowRegistration")
                            .append("source", "vessel-reg-process-dhow"),

                    new Document("content",
                            "General vessel registration process in Bahrain SSRP:\n" +
                                    "- Choose vessel type (Boat, Jet Ski, Dhow)\n" +
                                    "- Enter owner and vessel details\n" +
                                    "- Upload all required documents\n" +
                                    "- Submit the application\n" +
                                    "- Pay fees\n" +
                                    "- Inspection/verification if required\n" +
                                    "- Receive registration certificate")
                            .append("category", "VesselRegistration")
                            .append("source", "vessel-reg-process")
            );

            mongoTemplate.insert(kbDocs, collectionName);

            System.out.println("✅ KB documents inserted (without embeddings)");
        }
    }
}