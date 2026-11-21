package com.masterenglish.api.modules.practice.repository;

import com.masterenglish.api.modules.practice.entity.AttemptSnapshotOption;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AttemptSnapshotOptionRepository
        extends JpaRepository<AttemptSnapshotOption, UUID> {

    List<AttemptSnapshotOption> findByAttemptIdOrderByQuestionPositionAscPositionAsc(
            UUID attemptId);

    boolean existsByAttemptIdAndQuestionPositionAndId(
            UUID attemptId, int questionPosition, UUID id);
}
