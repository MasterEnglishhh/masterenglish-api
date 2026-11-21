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

    interface PickedQuestion {
        UUID getQuestionId();

        UUID getTopicId();
    }

    /**
     * Picks distinct published, non-archived questions for the topics. Each question appears once,
     * under one of its requested topics. The projection must match the current published revision.
     */
    @Query(
            value =
                    """
                    SELECT picked.question_id AS questionId, picked.topic_id AS topicId FROM (
                        SELECT DISTINCT ON (q.id) q.id AS question_id, qt.topic_id AS topic_id
                        FROM questions q
                        JOIN content_items ci ON ci.id = q.id AND ci.is_archived = false
                            AND ci.published_revision_id = q.published_revision_id
                        JOIN question_topics qt ON qt.question_id = q.id
                        WHERE qt.topic_id IN (:topicIds)
                        ORDER BY q.id, random()
                    ) picked
                    ORDER BY random()
                    LIMIT :limit
                    """,
            nativeQuery = true)
    List<PickedQuestion> pickQuestions(
            @Param("topicIds") Collection<UUID> topicIds, @Param("limit") int limit);

    @Query(
            value =
                    """
                    SELECT count(DISTINCT q.id) FROM questions q
                    JOIN content_items ci ON ci.id = q.id AND ci.is_archived = false
                        AND ci.published_revision_id = q.published_revision_id
                    JOIN question_topics qt ON qt.question_id = q.id
                    WHERE qt.topic_id = :topicId
                    """,
            nativeQuery = true)
    long countVisibleByTopicId(@Param("topicId") UUID topicId);
}
