package com.masterenglish.api.modules.identity.repository;

import com.masterenglish.api.modules.identity.entity.LearningGoal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface LearningGoalRepository extends JpaRepository<LearningGoal, UUID> {

    @Query(
            """
            SELECT u.id AS userId, u.email AS email, g.dailyMinutes AS dailyMinutes
            FROM LearningGoal g
            JOIN User u ON u.id = g.userId
            WHERE u.id IN :userIds
              AND u.status = com.masterenglish.api.modules.identity.entity.UserStatus.ACTIVE
            """)
    List<ActiveGoalRow> findActiveDailyGoalTargets(@Param("userIds") Collection<UUID> userIds);

    interface ActiveGoalRow {
        UUID getUserId();

        String getEmail();

        int getDailyMinutes();
    }
}
