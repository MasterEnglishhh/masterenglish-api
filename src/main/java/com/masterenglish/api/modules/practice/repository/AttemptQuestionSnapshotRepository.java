package com.masterenglish.api.modules.practice.repository;

import com.masterenglish.api.modules.practice.entity.AttemptQuestionSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AttemptQuestionSnapshotRepository
        extends JpaRepository<AttemptQuestionSnapshot, UUID> {

    List<AttemptQuestionSnapshot> findByAttemptIdOrderByOrdinalAsc(UUID attemptId);

    Optional<AttemptQuestionSnapshot> findByIdAndAttemptId(UUID id, UUID attemptId);
}
