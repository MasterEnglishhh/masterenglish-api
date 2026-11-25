package com.masterenglish.api.modules.practice.repository;

import com.masterenglish.api.modules.practice.entity.Question;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface QuestionRepository extends JpaRepository<Question, UUID> {

    /**
     * Eligible questions for the topics, locked {@code FOR SHARE} in id order. Eligible means: the
     * published revision is the visible one, the item is not archived or revoked, and the question
     * type is enabled. Ids are returned in ascending order so concurrent starts lock the same way.
     */
    @Query(
            value =
                    """
                    SELECT q.id FROM questions q
                    JOIN catalog_visible_items ci
                        ON ci.id = q.id AND ci.published_revision_id = q.published_revision_id
                    JOIN question_types ty ON ty.code = q.question_type_code AND ty.enabled
                    WHERE q.category = 'GRAMMAR'
                      AND q.id IN (
                          SELECT qt.question_id FROM question_topics qt
                          WHERE qt.topic_id IN (:topicIds))
                    ORDER BY q.id
                    FOR SHARE OF q
                    """,
            nativeQuery = true)
    List<UUID> lockEligibleQuestionIds(@Param("topicIds") Collection<UUID> topicIds);

    /**
     * Eligible questions among the given ids, locked {@code FOR SHARE} in id order. Used by
     * practice from mistakes: the question must still be the visible revision, enabled and not
     * archived.
     */
    @Query(
            value =
                    """
                    SELECT q.id FROM questions q
                    JOIN catalog_visible_items ci
                        ON ci.id = q.id AND ci.published_revision_id = q.published_revision_id
                    JOIN question_types ty ON ty.code = q.question_type_code AND ty.enabled
                    WHERE q.category = 'GRAMMAR' AND q.id IN (:questionIds)
                    ORDER BY q.id
                    FOR SHARE OF q
                    """,
            nativeQuery = true)
    List<UUID> lockEligibleQuestionIdsIn(@Param("questionIds") Collection<UUID> questionIds);

    /** Distinct eligible questions for one topic, the count shown on the exercise summary. */
    @Query(
            value =
                    """
                    SELECT count(DISTINCT q.id) FROM questions q
                    JOIN catalog_visible_items ci
                        ON ci.id = q.id AND ci.published_revision_id = q.published_revision_id
                    JOIN question_types ty ON ty.code = q.question_type_code AND ty.enabled
                    JOIN question_topics qt ON qt.question_id = q.id
                    WHERE q.category = 'GRAMMAR' AND qt.topic_id = :topicId
                    """,
            nativeQuery = true)
    long countEligibleByTopicId(@Param("topicId") UUID topicId);
}
