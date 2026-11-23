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
    String VISIBLE =
            "SELECT l.* FROM grammar_lessons l "
                    + "JOIN grammar_topics t ON t.id = l.topic_id "
                    + "JOIN catalog_visible_items tv "
                    + "ON tv.id = t.id AND tv.published_revision_id = t.published_revision_id "
                    + "JOIN catalog_visible_items lv "
                    + "ON lv.id = l.id AND lv.published_revision_id = l.published_revision_id ";

    @Query(
            value = VISIBLE + "WHERE l.topic_id = :topicId ORDER BY l.sort_order, l.title",
            nativeQuery = true)
    List<GrammarLesson> findVisibleByTopicId(@Param("topicId") UUID topicId);

    @Query(value = VISIBLE + "WHERE l.id = :lessonId", nativeQuery = true)
    Optional<GrammarLesson> findVisibleById(@Param("lessonId") UUID lessonId);
}
