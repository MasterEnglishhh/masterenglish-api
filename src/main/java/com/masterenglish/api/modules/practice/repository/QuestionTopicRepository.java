package com.masterenglish.api.modules.practice.repository;

import com.masterenglish.api.modules.practice.entity.QuestionTopic;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface QuestionTopicRepository extends JpaRepository<QuestionTopic, QuestionTopic.Id> {

    void deleteByIdQuestionId(UUID questionId);

    @Query("SELECT qt FROM QuestionTopic qt WHERE qt.id.questionId IN :questionIds")
    List<QuestionTopic> findByQuestionIds(@Param("questionIds") Collection<UUID> questionIds);
}
