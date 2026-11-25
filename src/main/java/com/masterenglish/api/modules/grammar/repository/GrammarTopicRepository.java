package com.masterenglish.api.modules.grammar.repository;

import com.masterenglish.api.modules.grammar.entity.GrammarTopic;
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
public interface GrammarTopicRepository extends JpaRepository<GrammarTopic, UUID> {

    /** Topics whose published revision is the one currently visible in the catalog view. */
    String VISIBLE =
            "SELECT t.* FROM grammar_topics t "
                    + "JOIN catalog_visible_items v "
                    + "ON v.id = t.id AND v.published_revision_id = t.published_revision_id ";

    String SEARCH_WHERE =
            "WHERE EXISTS (SELECT v.id FROM CatalogVisibleItem v"
                    + " WHERE v.id = t.id AND v.publishedRevisionId = t.publishedRevisionId)"
                    + " AND (cast(:difficulty as string) IS NULL OR t.difficulty = :difficulty)"
                    + " AND (cast(:pattern as string) IS NULL"
                    + " OR lower(t.name) LIKE cast(:pattern as string) ESCAPE '!')";

    /**
     * Visible topics filtered by difficulty and a name pattern. The pattern is pre-escaped with '!'
     * by the service; sorting uses entity property names from the controller allowlist.
     */
    @Query(
            value = "SELECT t FROM GrammarTopic t " + SEARCH_WHERE,
            countQuery = "SELECT count(t) FROM GrammarTopic t " + SEARCH_WHERE)
    Page<GrammarTopic> searchVisible(
            @Param("difficulty") String difficulty,
            @Param("pattern") String pattern,
            Pageable pageable);

    /** Read-only projection of visible topics for catalog aggregation. */
    @Query(
            value =
                    "SELECT t.id AS \"id\", t.name AS \"name\", t.sort_order AS \"sortOrder\""
                            + " FROM grammar_topics t"
                            + " JOIN catalog_visible_items v"
                            + " ON v.id = t.id AND v.published_revision_id = t.published_revision_id"
                            + " ORDER BY t.sort_order, t.name",
            nativeQuery = true)
    List<VisibleTopicRef> findVisibleTopicRefs();

    interface VisibleTopicRef {
        UUID getId();

        String getName();

        Integer getSortOrder();
    }

    @Query(value = VISIBLE + "WHERE t.id = :topicId", nativeQuery = true)
    Optional<GrammarTopic> findVisibleTopicById(@Param("topicId") UUID topicId);

    /** True when another topic already uses this slug; the slug is unique across topics. */
    boolean existsBySlugAndIdNot(String slug, UUID id);
}
