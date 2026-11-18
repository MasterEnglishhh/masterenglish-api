package com.masterenglish.api.modules.vocabulary.repository;

import com.masterenglish.api.modules.vocabulary.entity.Deck;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface DeckRepository extends JpaRepository<Deck, UUID> {

    @Query("SELECT d FROM Deck d JOIN CatalogVisibleItem i ON i.id = d.id")
    Page<Deck> findAllActive(Pageable pageable);

    @Query(
            "SELECT d FROM Deck d "
                    + "JOIN CatalogVisibleItem i ON i.id = d.id "
                    + "WHERE lower(d.title) LIKE lower(concat('%', :q, '%'))")
    Page<Deck> searchByTitle(@Param("q") String q, Pageable pageable);

    @Query(
            "SELECT d FROM Deck d "
                    + "JOIN CatalogVisibleItem i ON i.id = d.id "
                    + "WHERE d.level = :level")
    Page<Deck> searchByLevel(@Param("level") String level, Pageable pageable);

    @Query(
            "SELECT d FROM Deck d "
                    + "JOIN CatalogVisibleItem i ON i.id = d.id "
                    + "WHERE lower(d.title) LIKE lower(concat('%', :q, '%')) "
                    + "AND d.level = :level")
    Page<Deck> searchByTitleAndLevel(
            @Param("q") String q, @Param("level") String level, Pageable pageable);

    @Query(
            "SELECT d FROM Deck d "
                    + "JOIN CatalogVisibleItem i ON i.id = d.id "
                    + "WHERE d.id = :id")
    Optional<Deck> findActiveById(@Param("id") UUID id);
}
