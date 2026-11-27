package com.masterenglish.api.modules.learning.repository;

import com.masterenglish.api.modules.learning.entity.WordReview;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WordReviewRepository extends JpaRepository<WordReview, UUID> {
    Optional<WordReview> findByUserIdAndRequestId(UUID userId, UUID requestId);

    boolean existsByUserIdAndRequestId(UUID userId, UUID requestId);

    Page<WordReview> findByUserIdOrderByReviewedAtDescIdDesc(UUID userId, Pageable pageable);

    long countByUserId(UUID userId);

    long countByUserIdAndReviewedAtGreaterThanEqual(UUID userId, Instant since);

    @Query(
            value =
                    """
                    SELECT DISTINCT to_char((reviewed_at AT TIME ZONE :timezone)::date, 'YYYY-MM-DD')
                    FROM word_reviews
                    WHERE user_id = :userId
                    """,
            nativeQuery = true)
    List<String> findDistinctActivityLocalDays(
            @Param("userId") UUID userId, @Param("timezone") String timezone);
}
