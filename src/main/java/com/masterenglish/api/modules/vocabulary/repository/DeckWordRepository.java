package com.masterenglish.api.modules.vocabulary.repository;

import com.masterenglish.api.modules.vocabulary.entity.DeckWord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeckWordRepository extends JpaRepository<DeckWord, DeckWord.DeckWordId> {
    List<DeckWord> findByIdDeckIdOrderByPositionAsc(UUID deckId);

    long countByIdDeckId(UUID deckId);

    void deleteByIdDeckId(UUID deckId);
}
