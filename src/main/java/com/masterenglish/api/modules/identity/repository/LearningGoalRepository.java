package com.masterenglish.api.modules.identity.repository;

import com.masterenglish.api.modules.identity.entity.LearningGoal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LearningGoalRepository extends JpaRepository<LearningGoal, UUID> {}
