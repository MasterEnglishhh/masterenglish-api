package com.masterenglish.api.modules.grammar.repository;

import com.masterenglish.api.modules.grammar.entity.GrammarLesson;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface GrammarLessonRepository extends JpaRepository<GrammarLesson, UUID> {

    /** A lesson is visible only when it and its topic are both visible in the catalog view. */
    String VISIBLE_JOINS =
            "FROM grammar_lessons l "
                    + "JOIN grammar_topics t ON t.id = l.topic_id "
                    + "JOIN catalog_visible_items tv "
                    + "ON tv.id = t.id AND tv.published_revision_id = t.published_revision_id "
                    + "JOIN catalog_visible_items lv "
                    + "ON lv.id = l.id AND lv.published_revision_id = l.published_revision_id ";

    String VISIBLE = "SELECT l.* " + VISIBLE_JOINS;

    @Query(
            value = VISIBLE + "WHERE l.topic_id = :topicId ORDER BY l.sort_order, l.title",
            nativeQuery = true)
    List<GrammarLesson> findVisibleByTopicId(@Param("topicId") UUID topicId);

    @Query(value = VISIBLE + "WHERE l.id = :lessonId", nativeQuery = true)
    Optional<GrammarLesson> findVisibleById(@Param("lessonId") UUID lessonId);

    @Query(value = VISIBLE + "ORDER BY t.sort_order, l.sort_order, l.title", nativeQuery = true)
    List<GrammarLesson> findAllVisibleLessons();

    /** Read-only projection: callers never see managed entities, so no stale revision is reused. */
    @Query(
            value =
                    "SELECT l.id AS \"id\", l.topic_id AS \"topicId\","
                            + " l.published_revision_id AS \"publishedRevisionId\" "
                            + VISIBLE_JOINS
                            + "ORDER BY l.sort_order, l.title",
            nativeQuery = true)
    List<VisibleLessonRef> findAllVisibleRefs();

    interface VisibleLessonRef {
        UUID getId();

        UUID getTopicId();

        UUID getPublishedRevisionId();
    }
}
