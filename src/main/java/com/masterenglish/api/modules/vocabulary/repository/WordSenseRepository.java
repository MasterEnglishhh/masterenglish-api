package com.masterenglish.api.modules.vocabulary.repository;

import com.masterenglish.api.modules.vocabulary.entity.WordSense;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WordSenseRepository extends JpaRepository<WordSense, UUID> {
    List<WordSense> findByWordIdAndArchivedFalseOrderBySenseNoAsc(UUID wordId);

    Optional<WordSense> findByIdAndArchivedFalse(UUID id);
}
