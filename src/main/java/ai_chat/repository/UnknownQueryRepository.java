package ai_chat.repository;

import ai_chat.domain.UnknownQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UnknownQueryRepository extends MongoRepository<UnknownQuery, String> {

    Page<UnknownQuery> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
