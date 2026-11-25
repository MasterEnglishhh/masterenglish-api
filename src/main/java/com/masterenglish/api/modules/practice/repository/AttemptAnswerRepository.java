package com.masterenglish.api.modules.practice.repository;

import com.masterenglish.api.modules.practice.entity.AttemptAnswer;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AttemptAnswerRepository extends JpaRepository<AttemptAnswer, UUID> {

    List<AttemptAnswer> findByAttemptId(UUID attemptId);

    Optional<AttemptAnswer> findBySnapshotId(UUID snapshotId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AttemptAnswer a WHERE a.snapshotId = :snapshotId")
    Optional<AttemptAnswer> lockBySnapshotId(@Param("snapshotId") UUID snapshotId);

    /**
     * Compare-and-set: writes only when the stored version still equals {@code expectedVersion}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "UPDATE AttemptAnswer a SET a.selectedOptionId = :optionId,"
                    + " a.answeredAt = :now, a.updatedAt = :now, a.version = a.version + 1"
                    + " WHERE a.snapshotId = :snapshotId AND a.version = :expectedVersion")
    int chooseIfVersion(
            @Param("snapshotId") UUID snapshotId,
            @Param("expectedVersion") long expectedVersion,
            @Param("optionId") UUID optionId,
            @Param("now") Instant now);

    /** Clears the answer ({@code selectedOptionId = NULL}); still bumps the version. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "UPDATE AttemptAnswer a SET a.selectedOptionId = NULL, a.answeredAt = NULL,"
                    + " a.updatedAt = :now, a.version = a.version + 1"
                    + " WHERE a.snapshotId = :snapshotId AND a.version = :expectedVersion")
    int clearIfVersion(
            @Param("snapshotId") UUID snapshotId,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") Instant now);
}
