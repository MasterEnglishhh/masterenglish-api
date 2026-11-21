package com.masterenglish.api.modules.practice.repository;

import com.masterenglish.api.modules.practice.entity.QuestionOption;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface QuestionOptionRepository extends JpaRepository<QuestionOption, UUID> {

    List<QuestionOption> findByQuestionIdOrderByPositionAsc(UUID questionId);

    void deleteByQuestionId(UUID questionId);
}
