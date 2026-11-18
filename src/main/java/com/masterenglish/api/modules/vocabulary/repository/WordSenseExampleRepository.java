package com.masterenglish.api.modules.vocabulary.repository;

import com.masterenglish.api.modules.vocabulary.entity.WordSenseExample;
import com.masterenglish.api.modules.vocabulary.repository.fragment.WordSenseExampleOperations;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WordSenseExampleRepository
        extends JpaRepository<WordSenseExample, UUID>, WordSenseExampleOperations {
    @Modifying
    @Query("DELETE FROM WordSenseExample e WHERE e.wordSenseId=:senseId")
    void deleteBySenseId(@Param("senseId") UUID senseId);

    List<WordSenseExample> findByWordSenseIdOrderByPositionAsc(UUID wordSenseId);

    @Query("SELECT e.wordSenseId FROM WordSenseExample e WHERE e.id = :exampleId")
    Optional<UUID> findWordSenseId(@Param("exampleId") UUID exampleId);
}
