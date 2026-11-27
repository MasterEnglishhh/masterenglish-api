package com.masterenglish.api.modules.practice.repository;

import com.masterenglish.api.modules.practice.entity.PracticeSet;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PracticeSetRepository extends JpaRepository<PracticeSet, UUID> {
    Optional<PracticeSet> findByIdAndCategory(UUID id, String category);
}
