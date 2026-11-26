package com.masterenglish.api.modules.notifications.service.impl;

import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.common.events.IdentityEvents.DailyGoalTarget;
import com.masterenglish.api.common.events.LearningEvents;
import com.masterenglish.api.common.time.TimeZones;
import com.masterenglish.api.config.NotificationProperties;
import com.masterenglish.api.modules.notifications.entity.NotificationPreference;
import com.masterenglish.api.modules.notifications.entity.ReminderRule;
import com.masterenglish.api.modules.notifications.repository.NotificationPreferenceRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationRepository;
import com.masterenglish.api.modules.notifications.repository.ReminderRuleRepository;
import com.masterenglish.api.modules.notifications.service.ReminderPlanner;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ReminderPlannerImpl implements ReminderPlanner {

    static final String REVIEW_DUE_RULE = "REVIEW_DUE";
    static final String DAILY_GOAL_RULE = "DAILY_GOAL";
    private static final UUID FIRST_KEY = new UUID(0, 0);

    private final NotificationPreferenceRepository preferences;
    private final NotificationRepository notifications;
    private final ReminderRuleRepository rules;
    private final ReminderWriter writer;
    private final IdentityEvents identity;
    private final LearningEvents learning;
    private final NotificationProperties properties;

    public ReminderPlannerImpl(
            NotificationPreferenceRepository preferences,
            NotificationRepository notifications,
            ReminderRuleRepository rules,
            ReminderWriter writer,
            IdentityEvents identity,
            LearningEvents learning,
            NotificationProperties properties) {
        this.preferences = preferences;
        this.notifications = notifications;
        this.rules = rules;
        this.writer = writer;
        this.identity = identity;
        this.learning = learning;
        this.properties = properties;
    }

    @Override
    public int planReviewDueReminders(Instant now) {
        return runRule(
                REVIEW_DUE_RULE, now, "review", (rule, page) -> planReviewDuePage(page, rule, now));
    }

    @Override
    public int planDailyGoalReminders(Instant now) {
        return runRule(
                DAILY_GOAL_RULE,
                now,
                "daily goal",
                (rule, page) -> planDailyGoalPage(page, rule, now));
    }

    private int runRule(
            String ruleCode,
            Instant now,
            String label,
            BiFunction<ReminderRule, List<NotificationPreference>, Integer> planner) {
        Optional<ReminderRule> rule = rules.findByCode(ruleCode).filter(ReminderRule::isEnabled);
        if (rule.isEmpty()) {
            return 0;
        }
        int created = 0;
        UUID after = FIRST_KEY;
        while (true) {
            List<NotificationPreference> page =
                    preferences.findReminderCandidates(
                            after, PageRequest.of(0, properties.schedulerBatchSize()));
            if (page.isEmpty()) {
                break;
            }
            after = page.getLast().getUserId();
            created += planner.apply(rule.get(), page);
        }
        if (created > 0) {
            log.info("Created {} {} reminders", created, label);
        }
        return created;
    }

    private int planReviewDuePage(
            List<NotificationPreference> page, ReminderRule rule, Instant now) {
        Map<UUID, Candidate> candidates =
                filterRuleCandidates(page, rule, now, ReminderWriter::reviewDueKey);
        if (candidates.isEmpty()) {
            return 0;
        }

        Map<UUID, String> activeEmails = identity.findActiveEmails(candidates.keySet());
        candidates.keySet().retainAll(activeEmails.keySet());
        if (candidates.isEmpty()) {
            return 0;
        }

        Map<UUID, Long> dueCards = learning.countDueCards(candidates.keySet(), now);
        int created = 0;
        for (Candidate candidate : candidates.values()) {
            long due = dueCards.getOrDefault(candidate.userId(), 0L);
            if (due <= 0) {
                continue;
            }
            String recipient = candidate.email() ? activeEmails.get(candidate.userId()) : null;
            if (writer.createReviewDue(
                    candidate.userId(),
                    rule.getId(),
                    rule.getMinimumGapMinutes(),
                    candidate.localDay(),
                    due,
                    candidate.preference().isInAppEnabled(),
                    recipient,
                    now)) {
                created++;
            }
        }
        return created;
    }

    private int planDailyGoalPage(
            List<NotificationPreference> page, ReminderRule rule, Instant now) {
        Map<UUID, Candidate> candidates =
                filterRuleCandidates(page, rule, now, ReminderWriter::dailyGoalKey);
        if (candidates.isEmpty()) {
            return 0;
        }

        Map<UUID, DailyGoalTarget> targets = identity.findDailyGoalTargets(candidates.keySet());
        candidates.keySet().retainAll(targets.keySet());
        if (candidates.isEmpty()) {
            return 0;
        }

        Map<UUID, LocalDate> userLocalDays = new LinkedHashMap<>();
        candidates.forEach((userId, candidate) -> userLocalDays.put(userId, candidate.localDay()));
        Map<UUID, Long> studySeconds = learning.sumDailyStudySeconds(userLocalDays);

        int created = 0;
        for (Candidate candidate : candidates.values()) {
            DailyGoalTarget target = targets.get(candidate.userId());
            if (target == null || target.dailyMinutes() <= 0) {
                continue;
            }
            long actualSeconds = studySeconds.getOrDefault(candidate.userId(), 0L);
            int actualMinutes = (int) Math.floorDiv(actualSeconds, 60L);
            if (actualMinutes >= target.dailyMinutes()) {
                continue;
            }
            int remainingMinutes = target.dailyMinutes() - actualMinutes;
            String recipient = candidate.email() ? target.email() : null;
            if (writer.createDailyGoal(
                    candidate.userId(),
                    rule.getId(),
                    rule.getMinimumGapMinutes(),
                    candidate.localDay(),
                    target.dailyMinutes(),
                    actualMinutes,
                    remainingMinutes,
                    candidate.preference().isInAppEnabled(),
                    recipient,
                    now)) {
                created++;
            }
        }
        return created;
    }

    private Map<UUID, Candidate> filterRuleCandidates(
            List<NotificationPreference> page,
            ReminderRule rule,
            Instant now,
            BiFunction<UUID, LocalDate, String> keyFactory) {
        boolean mailEnabled = properties.mail().deliverable();
        Map<UUID, Candidate> candidates = new LinkedHashMap<>();
        for (NotificationPreference preference : page) {
            boolean wantsEmail = preference.isEmailEnabled() && mailEnabled;
            if (!preference.isInAppEnabled() && !wantsEmail) {
                continue;
            }
            localDayIfDue(preference, now)
                    .ifPresent(
                            day -> {
                                UUID userId = preference.getUserId();
                                candidates.put(
                                        userId,
                                        new Candidate(
                                                preference,
                                                day,
                                                wantsEmail,
                                                keyFactory.apply(userId, day)));
                            });
        }
        if (candidates.isEmpty()) {
            return candidates;
        }

        Set<DedupeKey> existing =
                notifications
                        .findExistingDedupeKeys(
                                candidates.keySet(),
                                candidates.values().stream().map(Candidate::dedupeKey).toList())
                        .stream()
                        .map(row -> new DedupeKey(row.getUserId(), row.getDedupeKey()))
                        .collect(Collectors.toSet());
        candidates
                .values()
                .removeIf(
                        candidate ->
                                existing.contains(
                                        new DedupeKey(candidate.userId(), candidate.dedupeKey())));

        Instant gapStart = now.minus(Duration.ofMinutes(rule.getMinimumGapMinutes()));
        if (!candidates.isEmpty()) {
            notifications.findLastReminders(rule.getId(), candidates.keySet()).stream()
                    .filter(last -> last.getLastCreatedAt().isAfter(gapStart))
                    .forEach(last -> candidates.remove(last.getUserId()));
        }
        return candidates;
    }

    private record DedupeKey(UUID userId, String dedupeKey) {}

    /** The learner's local day when the reminder time has passed outside quiet hours. */
    static Optional<LocalDate> localDayIfDue(NotificationPreference preference, Instant now) {
        String zoneId = preference.getTimezone();
        if (zoneId == null || zoneId.isBlank()) {
            log.warn("Skipping reminder for user {}: blank timezone", preference.getUserId());
            return Optional.empty();
        }
        Optional<ZoneId> resolved = TimeZones.resolveCanonicalZone(zoneId);
        if (resolved.isEmpty()) {
            log.warn("Skipping reminder for user {}: invalid timezone", preference.getUserId());
            return Optional.empty();
        }
        ZonedDateTime local = now.atZone(resolved.get());
        LocalTime time = local.toLocalTime();
        if (time.isBefore(preference.getDailyReminderTime())) {
            return Optional.empty();
        }
        if (inQuietHours(time, preference.getQuietHoursStart(), preference.getQuietHoursEnd())) {
            return Optional.empty();
        }
        return Optional.of(local.toLocalDate());
    }

    /** Quiet hours are [start, end); a start after the end spans midnight. */
    static boolean inQuietHours(LocalTime time, LocalTime start, LocalTime end) {
        if (start == null || end == null) {
            return false;
        }
        if (start.isBefore(end)) {
            return !time.isBefore(start) && time.isBefore(end);
        }
        return !time.isBefore(start) || time.isBefore(end);
    }

    private record Candidate(
            NotificationPreference preference,
            LocalDate localDay,
            boolean email,
            String dedupeKey) {
        UUID userId() {
            return preference.getUserId();
        }
    }
}
