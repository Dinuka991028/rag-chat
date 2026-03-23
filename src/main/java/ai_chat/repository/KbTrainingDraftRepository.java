package ai_chat.repository;

import ai_chat.domain.KbTrainingDraft;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;

public interface KbTrainingDraftRepository extends MongoRepository<KbTrainingDraft, String> {

    Page<KbTrainingDraft> findByStatusOrderByCreatedAtDesc(KbTrainingDraft.Status status, Pageable pageable);

    boolean existsByNormalizedQuestionAndStatusIn(
            String normalizedQuestion,
            Collection<KbTrainingDraft.Status> statuses);
}
