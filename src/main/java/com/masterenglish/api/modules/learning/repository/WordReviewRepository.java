package com.masterenglish.api.modules.learning.repository;

import com.masterenglish.api.modules.learning.entity.WordReview;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WordReviewRepository extends JpaRepository<WordReview, UUID> {
    Optional<WordReview> findByUserIdAndRequestId(UUID userId, UUID requestId);

    boolean existsByUserIdAndRequestId(UUID userId, UUID requestId);

    Page<WordReview> findByUserIdOrderByReviewedAtDescIdDesc(UUID userId, Pageable pageable);

    long countByUserId(UUID userId);

    long countByUserIdAndReviewedAtGreaterThanEqual(UUID userId, Instant since);
}
