package com.masterenglish.api.modules.vocabulary.repository;

import com.masterenglish.api.modules.vocabulary.entity.WordSenseExample;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WordSenseExampleRepository extends JpaRepository<WordSenseExample, UUID> {
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "DELETE FROM WordSenseExample e WHERE e.wordSenseId=:senseId")
    void deleteBySenseId(@org.springframework.data.repository.query.Param("senseId") UUID senseId);

    List<WordSenseExample> findByWordSenseIdOrderByPositionAsc(UUID wordSenseId);
}
