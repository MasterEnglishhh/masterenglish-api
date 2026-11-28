package com.masterenglish.api.modules.practice.repository;

import com.masterenglish.api.modules.practice.entity.QuestionGroup;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface QuestionGroupRepository extends JpaRepository<QuestionGroup, UUID> {
    Optional<QuestionGroup> findByIdAndCategory(UUID id, String category);
}
