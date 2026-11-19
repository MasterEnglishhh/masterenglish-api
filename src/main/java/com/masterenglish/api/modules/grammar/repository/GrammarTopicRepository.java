package com.masterenglish.api.modules.grammar.repository;

import com.masterenglish.api.modules.grammar.entity.GrammarTopic;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface GrammarTopicRepository extends JpaRepository<GrammarTopic, UUID> {

    /** Topics whose published revision is the one currently visible in the catalog view. */
    String VISIBLE =
            "SELECT t.* FROM grammar_topics t "
                    + "JOIN catalog_visible_items v "
                    + "ON v.id = t.id AND v.published_revision_id = t.published_revision_id ";

    @Query(value = VISIBLE + "ORDER BY t.sort_order, t.name", nativeQuery = true)
    List<GrammarTopic> findVisibleTopics();

    @Query(value = VISIBLE + "WHERE t.id = :topicId", nativeQuery = true)
    Optional<GrammarTopic> findVisibleTopicById(@Param("topicId") UUID topicId);

    /** True when another topic already uses this slug; the slug is unique across topics. */
    boolean existsBySlugAndIdNot(String slug, UUID id);
}
