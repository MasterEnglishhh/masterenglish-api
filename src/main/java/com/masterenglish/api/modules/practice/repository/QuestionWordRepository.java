package com.masterenglish.api.modules.practice.repository;

import com.masterenglish.api.modules.practice.entity.QuestionWord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface QuestionWordRepository extends JpaRepository<QuestionWord, QuestionWord.Id> {

    void deleteByIdQuestionId(UUID questionId);

    List<QuestionWord> findByIdWordSenseId(UUID wordSenseId);

    List<QuestionWord> findByIdQuestionId(UUID questionId);
}
