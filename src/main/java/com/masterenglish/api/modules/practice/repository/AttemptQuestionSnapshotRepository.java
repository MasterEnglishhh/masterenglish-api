package com.masterenglish.api.modules.practice.repository;

import com.masterenglish.api.modules.practice.entity.AttemptQuestionSnapshot;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AttemptQuestionSnapshotRepository
        extends JpaRepository<AttemptQuestionSnapshot, AttemptQuestionSnapshot.Id> {

    List<AttemptQuestionSnapshot> findByIdAttemptIdOrderByIdPositionAsc(UUID attemptId);
}
