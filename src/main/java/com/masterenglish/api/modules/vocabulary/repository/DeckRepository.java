package com.masterenglish.api.modules.vocabulary.repository;

import com.masterenglish.api.modules.vocabulary.entity.Deck;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeckRepository extends JpaRepository<Deck, UUID> {

    @Query(
            "SELECT d FROM Deck d "
                    + "JOIN com.masterenglish.api.modules.content.entity.ContentItem ci ON ci.id = d.id "
                    + "WHERE ci.archived = false AND ci.accessRevokedAt IS NULL")
    Page<Deck> findAllActive(Pageable pageable);

    @Query(
            "SELECT d FROM Deck d "
                    + "JOIN com.masterenglish.api.modules.content.entity.ContentItem ci ON ci.id = d.id "
                    + "WHERE ci.archived = false AND ci.accessRevokedAt IS NULL "
                    + "AND lower(d.title) LIKE lower(concat('%', :q, '%'))")
    Page<Deck> searchByTitle(@Param("q") String q, Pageable pageable);

    @Query(
            "SELECT d FROM Deck d "
                    + "JOIN com.masterenglish.api.modules.content.entity.ContentItem ci ON ci.id = d.id "
                    + "WHERE ci.archived = false AND ci.accessRevokedAt IS NULL "
                    + "AND d.level = :level")
    Page<Deck> searchByLevel(@Param("level") String level, Pageable pageable);

    @Query(
            "SELECT d FROM Deck d "
                    + "JOIN com.masterenglish.api.modules.content.entity.ContentItem ci ON ci.id = d.id "
                    + "WHERE ci.archived = false AND ci.accessRevokedAt IS NULL "
                    + "AND lower(d.title) LIKE lower(concat('%', :q, '%')) "
                    + "AND d.level = :level")
    Page<Deck> searchByTitleAndLevel(
            @Param("q") String q, @Param("level") String level, Pageable pageable);

    @Query(
            "SELECT d FROM Deck d "
                    + "JOIN com.masterenglish.api.modules.content.entity.ContentItem ci ON ci.id = d.id "
                    + "WHERE d.id = :id AND ci.archived = false AND ci.accessRevokedAt IS NULL")
    Optional<Deck> findActiveById(@Param("id") UUID id);
}
