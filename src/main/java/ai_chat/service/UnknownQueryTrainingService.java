package ai_chat.service;

import ai_chat.config.UnknownTrainingProperties;
import ai_chat.domain.KbTrainingDraft;
import ai_chat.domain.UnknownQuery;
import ai_chat.dto.AddKnowledgeRequest;
import ai_chat.repository.KbTrainingDraftRepository;
import ai_chat.repository.UnknownQueryRepository;
import ai_chat.training.UnknownQueryNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Periodic trainer that turns repeated unknown queries into reviewable KB drafts.
 */
@Service
public class UnknownQueryTrainingService {

    private static final Logger log = LoggerFactory.getLogger(UnknownQueryTrainingService.class);
    private static final int DRAFT_CONTEXT_TOP_K = 6;
    private static final double DRAFT_CONTEXT_THRESHOLD = SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL;
    private static final Set<KbTrainingDraft.Status> OPEN_DRAFT_STATUSES =
            Set.of(KbTrainingDraft.Status.PENDING, KbTrainingDraft.Status.APPROVED, KbTrainingDraft.Status.IMPORTED);

    private static final String TRAINING_SYSTEM_PROMPT =
            "You are generating an internal KB draft for support agents.\n"
                    + "Use ONLY the provided internal knowledge excerpts.\n"
                    + "If the excerpts are missing, weak, or not directly relevant, return exactly: EMPTY\n"
                    + "Do not invent facts, policies, steps, or URLs.\n"
                    + "Write a concise answer in 2-5 sentences.";

    private final UnknownTrainingProperties properties;
    private final UnknownQueryRepository unknownQueryRepository;
    private final KbTrainingDraftRepository kbTrainingDraftRepository;
    private final ChatModel chatModel;
    private final VectorStore vectorStore;
    private final KnowledgeAdminService knowledgeAdminService;

    public UnknownQueryTrainingService(
            UnknownTrainingProperties properties,
            UnknownQueryRepository unknownQueryRepository,
            KbTrainingDraftRepository kbTrainingDraftRepository,
            ChatModel chatModel,
            VectorStore vectorStore,
            KnowledgeAdminService knowledgeAdminService) {
        this.properties = properties;
        this.unknownQueryRepository = unknownQueryRepository;
        this.kbTrainingDraftRepository = kbTrainingDraftRepository;
        this.chatModel = chatModel;
        this.vectorStore = vectorStore;
        this.knowledgeAdminService = knowledgeAdminService;
    }

    @Scheduled(cron = "${conf.unknown-training.cron:0 0 * * * *}")
    public void runTraining() {
        if (!properties.isEnabled()) {
            return;
        }

        int size = Math.max(1, properties.getBatchSize());
        Pageable batchPage = PageRequest.of(0, size);
        List<UnknownQuery> batch = unknownQueryRepository.findAllByOrderByCreatedAtDesc(batchPage).getContent();
        if (batch.isEmpty()) {
            return;
        }

        List<UnknownQueryNormalizer.GroupedUnknownQuestion> groups =
                UnknownQueryNormalizer.eligibleGroups(batch, properties.getMinFrequency());
        int draftsCreated = 0;
        int imported = 0;

        for (UnknownQueryNormalizer.GroupedUnknownQuestion group : groups) {
            boolean alreadyTracked = kbTrainingDraftRepository.existsByNormalizedQuestionAndStatusIn(
                    group.normalizedQuestion(),
                    OPEN_DRAFT_STATUSES);
            if (alreadyTracked) {
                continue;
            }

            String answer = generateDraftAnswer(group.question());
            if (answer == null) {
                continue;
            }

            if (properties.isAutoApprove()) {
                try {
                    AddKnowledgeRequest req = new AddKnowledgeRequest();
                    req.setTitle(group.question());
                    req.setContent(answer);
                    req.setCategory("UnknownTraining");
                    req.setSource("unknown-training");
                    knowledgeAdminService.addTextEntry(req);

                    KbTrainingDraft importedDraft = KbTrainingDraft.builder()
                            .question(group.question())
                            .normalizedQuestion(group.normalizedQuestion())
                            .count(group.count())
                            .proposedAnswer(answer)
                            .source("unknown-training")
                            .status(KbTrainingDraft.Status.IMPORTED)
                            .createdAt(new Date())
                            .updatedAt(new Date())
                            .build();
                    kbTrainingDraftRepository.save(importedDraft);
                    imported++;
                } catch (Exception e) {
                    log.warn("Unknown-training auto-approve import failed for [{}]: {}", group.question(), e.getMessage());
                }
            } else {
                KbTrainingDraft draft = KbTrainingDraft.builder()
                        .question(group.question())
                        .normalizedQuestion(group.normalizedQuestion())
                        .count(group.count())
                        .proposedAnswer(answer)
                        .source("unknown-training")
                        .status(KbTrainingDraft.Status.PENDING)
                        .createdAt(new Date())
                        .updatedAt(new Date())
                        .build();
                kbTrainingDraftRepository.save(draft);
                draftsCreated++;
            }
        }

        if (properties.isDeleteProcessedUnknowns()) {
            unknownQueryRepository.deleteAll(batch);
        }

        if (draftsCreated > 0 || imported > 0) {
            log.info("Unknown-training run complete: pendingDraftsCreated={}, imported={}", draftsCreated, imported);
        }
    }

    private String generateDraftAnswer(String question) {
        List<Document> context = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(DRAFT_CONTEXT_TOP_K)
                        .similarityThreshold(DRAFT_CONTEXT_THRESHOLD)
                        .build());
        if (context == null || context.isEmpty()) {
            return null;
        }

        StringBuilder excerpts = new StringBuilder();
        for (Document d : context) {
            if (d.getText() != null && !d.getText().isBlank()) {
                excerpts.append(d.getText()).append("\n\n");
            }
        }
        if (excerpts.length() == 0) {
            return null;
        }

        String userPrompt = "Internal knowledge excerpts:\n\n"
                + excerpts
                + "Customer question:\n"
                + question
                + "\n\nReturn only the answer text, or EMPTY.";
        try {
            ChatResponse response = chatModel.call(new Prompt(
                    new SystemMessage(TRAINING_SYSTEM_PROMPT),
                    new UserMessage(userPrompt)));
            String text = extractAssistantText(response);
            if (text == null) {
                return null;
            }
            String t = text.trim();
            if (t.isEmpty() || "EMPTY".equalsIgnoreCase(t)) {
                return null;
            }
            return t;
        } catch (Exception e) {
            log.warn("Unknown-training draft generation failed for [{}]: {}", question, e.getMessage());
            return null;
        }
    }

    private static String extractAssistantText(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return null;
        }
        var output = response.getResult().getOutput();
        if (output == null) {
            return null;
        }
        return output.getText();
    }
}
