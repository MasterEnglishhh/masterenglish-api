package com.masterenglish.api.modules.notifications.service.impl;

import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.common.events.LearningEvents;
import com.masterenglish.api.config.NotificationProperties;
import com.masterenglish.api.modules.notifications.entity.NotificationPreference;
import com.masterenglish.api.modules.notifications.entity.ReminderRule;
import com.masterenglish.api.modules.notifications.repository.NotificationPreferenceRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationRepository;
import com.masterenglish.api.modules.notifications.repository.ReminderRuleRepository;
import com.masterenglish.api.modules.notifications.service.ReminderPlanner;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ReminderPlannerImpl implements ReminderPlanner {

    static final String REVIEW_DUE_RULE = "REVIEW_DUE";
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
        Optional<ReminderRule> rule =
                rules.findByCode(REVIEW_DUE_RULE).filter(ReminderRule::isEnabled);
        if (rule.isEmpty()) return 0;
        int created = 0;
        UUID after = FIRST_KEY;
        while (true) {
            List<NotificationPreference> page =
                    preferences.findReminderCandidates(
                            after, PageRequest.of(0, properties.schedulerBatchSize()));
            if (page.isEmpty()) break;
            after = page.getLast().getUserId();
            created += planPage(page, rule.get(), now);
        }
        if (created > 0) log.info("Created {} review reminders", created);
        return created;
    }

    private int planPage(List<NotificationPreference> page, ReminderRule rule, Instant now) {
        boolean mailEnabled = properties.mail().deliverable();
        Map<UUID, Candidate> candidates = new LinkedHashMap<>();
        for (NotificationPreference preference : page) {
            boolean wantsEmail = preference.isEmailEnabled() && mailEnabled;
            if (!preference.isInAppEnabled() && !wantsEmail) continue;
            localDayIfDue(preference, now)
                    .ifPresent(
                            day ->
                                    candidates.put(
                                            preference.getUserId(),
                                            new Candidate(preference, day, wantsEmail)));
        }
        if (candidates.isEmpty()) return 0;

        Set<String> existing =
                new HashSet<>(
                        notifications.findExistingDedupeKeys(
                                candidates.values().stream().map(Candidate::dedupeKey).toList()));
        candidates.values().removeIf(candidate -> existing.contains(candidate.dedupeKey()));

        Instant gapStart = now.minus(Duration.ofMinutes(rule.getMinimumGapMinutes()));
        if (!candidates.isEmpty()) {
            notifications.findLastReminders(rule.getId(), candidates.keySet()).stream()
                    .filter(last -> last.getLastCreatedAt().isAfter(gapStart))
                    .forEach(last -> candidates.remove(last.getUserId()));
        }
        if (candidates.isEmpty()) return 0;

        Map<UUID, String> activeEmails = identity.findActiveEmails(candidates.keySet());
        candidates.keySet().retainAll(activeEmails.keySet());
        if (candidates.isEmpty()) return 0;

        Map<UUID, Long> dueCards = learning.countDueCards(candidates.keySet(), now);
        int created = 0;
        for (Candidate candidate : candidates.values()) {
            long due = dueCards.getOrDefault(candidate.userId(), 0L);
            if (due <= 0) continue;
            String recipient = candidate.email() ? activeEmails.get(candidate.userId()) : null;
            if (writer.createReviewDue(
                    candidate.userId(),
                    rule.getId(),
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

    /** The learner's local day when the reminder time has passed outside quiet hours. */
    static Optional<LocalDate> localDayIfDue(NotificationPreference preference, Instant now) {
        String zoneId = preference.getTimezone();
        if (zoneId == null || zoneId.isBlank()) {
            log.warn("Skipping reminder for user {}: blank timezone", preference.getUserId());
            return Optional.empty();
        }
        ZonedDateTime local;
        try {
            local = now.atZone(ZoneId.of(zoneId));
        } catch (DateTimeException exception) {
            log.warn("Skipping reminder for user {}: invalid timezone", preference.getUserId());
            return Optional.empty();
        }
        LocalTime time = local.toLocalTime();
        if (time.isBefore(preference.getDailyReminderTime())) return Optional.empty();
        if (inQuietHours(time, preference.getQuietHoursStart(), preference.getQuietHoursEnd())) {
            return Optional.empty();
        }
        return Optional.of(local.toLocalDate());
    }

    /** Quiet hours are [start, end); a start after the end spans midnight. */
    static boolean inQuietHours(LocalTime time, LocalTime start, LocalTime end) {
        if (start == null || end == null) return false;
        if (start.isBefore(end)) return !time.isBefore(start) && time.isBefore(end);
        return !time.isBefore(start) || time.isBefore(end);
    }

    private record Candidate(NotificationPreference preference, LocalDate localDay, boolean email) {
        UUID userId() {
            return preference.getUserId();
        }

        String dedupeKey() {
            return ReminderWriter.reviewDueKey(userId(), localDay);
        }
    }
}
