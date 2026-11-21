package com.masterenglish.api.modules.practice.repository;

import com.masterenglish.api.modules.practice.entity.AttemptAnswer;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AttemptAnswerRepository extends JpaRepository<AttemptAnswer, AttemptAnswer.Id> {

    List<AttemptAnswer> findByIdAttemptId(UUID attemptId);

    @Query(
            value =
                    """
                    SELECT count(*) FROM attempt_answers ans
                    JOIN attempt_snapshot_options o ON o.attempt_id = ans.attempt_id
                        AND o.question_position = ans.question_position
                        AND o.id = ans.selected_option_id
                    WHERE ans.attempt_id = :attemptId AND o.is_correct
                    """,
            nativeQuery = true)
    long countCorrectAnswers(@Param("attemptId") UUID attemptId);
}
