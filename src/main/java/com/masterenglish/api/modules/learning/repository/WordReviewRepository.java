package com.masterenglish.api.modules.learning.repository;

import com.masterenglish.api.modules.learning.entity.WordReview;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WordReviewRepository extends JpaRepository<WordReview, UUID> {
    Optional<WordReview> findByUserIdAndRequestId(UUID userId, UUID requestId);

    boolean existsByUserIdAndRequestId(UUID userId, UUID requestId);
}
