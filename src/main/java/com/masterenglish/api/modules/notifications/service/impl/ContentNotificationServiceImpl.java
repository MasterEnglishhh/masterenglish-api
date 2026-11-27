package com.masterenglish.api.modules.notifications.service.impl;

import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.config.NotificationProperties;
import com.masterenglish.api.modules.notifications.entity.NotificationPreference;
import com.masterenglish.api.modules.notifications.repository.NotificationPreferenceRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationRepository;
import com.masterenglish.api.modules.notifications.service.ContentNotificationService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ContentNotificationServiceImpl implements ContentNotificationService {

    private static final UUID FIRST_KEY = new UUID(0, 0);

    private final ContentEvents content;
    private final IdentityEvents identity;
    private final NotificationPreferenceRepository preferences;
    private final NotificationRepository notifications;
    private final ReminderWriter writer;
    private final NotificationProperties properties;
    private final Clock clock;

    public ContentNotificationServiceImpl(
            ContentEvents content,
            IdentityEvents identity,
            NotificationPreferenceRepository preferences,
            NotificationRepository notifications,
            ReminderWriter writer,
            NotificationProperties properties,
            Clock clock) {
        this.content = Objects.requireNonNull(content);
        this.identity = Objects.requireNonNull(identity);
        this.preferences = Objects.requireNonNull(preferences);
        this.notifications = Objects.requireNonNull(notifications);
        this.writer = Objects.requireNonNull(writer);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public int notifyDeckFirstPublished(
            UUID deckId, UUID revisionId, String deckTitle, String level) {
        Objects.requireNonNull(deckId, "deckId must not be null");
        if (!content.isItemActive(deckId)) {
            log.debug("Skipping CONTENT_NEW for inactive or revoked deck {}", deckId);
            return 0;
        }

        Instant now = clock.instant();
        boolean mailDeliverable = properties.mail().deliverable();
        int created = 0;
        UUID after = FIRST_KEY;
        while (true) {
            List<NotificationPreference> page =
                    preferences.findContentNotificationCandidates(
                            after, PageRequest.of(0, properties.schedulerBatchSize()));
            if (page.isEmpty()) {
                break;
            }
            after = page.getLast().getUserId();
            created += dispatchPage(page, deckId, deckTitle, level, mailDeliverable, now);
        }
        if (created > 0) {
            log.info("Created {} CONTENT_NEW notifications for deck {}", created, deckId);
        }
        return created;
    }

    private int dispatchPage(
            List<NotificationPreference> page,
            UUID deckId,
            String deckTitle,
            String level,
            boolean mailDeliverable,
            Instant now) {
        Map<UUID, NotificationPreference> deliverablePrefs = new HashMap<>();
        List<String> expectedKeys = new ArrayList<>();
        for (NotificationPreference pref : page) {
            boolean wantsEmail = pref.isEmailEnabled() && mailDeliverable;
            if (!pref.isInAppEnabled() && !wantsEmail) {
                continue;
            }
            deliverablePrefs.put(pref.getUserId(), pref);
            expectedKeys.add(ReminderWriter.contentNewDeckKey(pref.getUserId(), deckId));
        }
        if (deliverablePrefs.isEmpty()) {
            return 0;
        }

        Set<UUID> existingUsers =
                notifications
                        .findExistingDedupeKeys(deliverablePrefs.keySet(), expectedKeys)
                        .stream()
                        .map(NotificationRepository.UserDedupeKey::getUserId)
                        .collect(Collectors.toSet());
        deliverablePrefs.keySet().removeAll(existingUsers);
        if (deliverablePrefs.isEmpty()) {
            return 0;
        }

        Map<UUID, String> eligibleLearners =
                identity.findActiveLearnerEmails(deliverablePrefs.keySet());
        int created = 0;
        for (Map.Entry<UUID, String> recipient : eligibleLearners.entrySet()) {
            NotificationPreference pref = deliverablePrefs.get(recipient.getKey());
            if (pref == null) {
                continue;
            }
            String emailRecipient =
                    pref.isEmailEnabled() && mailDeliverable ? recipient.getValue() : null;
            if (writer.createContentNewDeck(
                    recipient.getKey(),
                    deckId,
                    deckTitle,
                    level,
                    pref.isInAppEnabled(),
                    emailRecipient,
                    now)) {
                created++;
            }
        }
        return created;
    }
}
