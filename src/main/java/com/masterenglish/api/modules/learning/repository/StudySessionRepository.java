package com.masterenglish.api.modules.learning.repository;

import com.masterenglish.api.modules.learning.entity.StudySession;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StudySessionRepository extends JpaRepository<StudySession, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM StudySession s WHERE s.id = :id AND s.userId = :userId")
    Optional<StudySession> findByIdAndUserIdForUpdate(
            @Param("id") UUID id, @Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM StudySession s WHERE s.userId = :userId AND s.closedAt IS NULL")
    Optional<StudySession> findOpenByUserIdForUpdate(@Param("userId") UUID userId);

    @Query(
            """
            SELECT COALESCE(SUM(s.activeSeconds), 0)
            FROM StudySession s
            WHERE s.userId = :userId AND s.localDay = :localDay
            """)
    BigDecimal sumActiveSecondsForUserAndDay(
            @Param("userId") UUID userId, @Param("localDay") LocalDate localDay);

    @Query(
            """
            SELECT s.userId AS userId, s.localDay AS localDay, SUM(s.activeSeconds) AS activeSeconds
            FROM StudySession s
            WHERE s.userId IN :userIds AND s.localDay IN :localDays
            GROUP BY s.userId, s.localDay
            """)
    List<DailyUserStudySecondsRow> sumActiveSecondsByUsersAndDays(
            @Param("userIds") Collection<UUID> userIds,
            @Param("localDays") Collection<LocalDate> localDays);

    @Query(
            "SELECT DISTINCT s.localDay FROM StudySession s WHERE s.userId = :userId AND s.activeSeconds > 0")
    List<LocalDate> findDistinctLocalDaysWithActiveStudy(@Param("userId") UUID userId);

    interface DailyUserStudySecondsRow {
        UUID getUserId();

        LocalDate getLocalDay();

        BigDecimal getActiveSeconds();
    }
}
