package com.masterenglish.api.modules.notifications.repository;

import com.masterenglish.api.modules.notifications.entity.ReminderRule;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReminderRuleRepository extends JpaRepository<ReminderRule, UUID> {

    Optional<ReminderRule> findByCode(String code);
}
