package com.masterenglish.api.modules.notifications.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.events.ContentEvents.DeckDraft;
import com.masterenglish.api.common.events.ContentEvents.WordDraft;
import com.masterenglish.api.modules.content.service.ContentAuthoringService;
import com.masterenglish.api.modules.notifications.entity.DeliveryChannel;
import com.masterenglish.api.modules.notifications.entity.DeliveryStatus;
import com.masterenglish.api.modules.notifications.entity.NotificationPreference;
import com.masterenglish.api.modules.notifications.repository.NotificationDeliveryRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationPreferenceRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationRepository;
import com.masterenglish.api.modules.vocabulary.service.VocabularySeedService;
import com.masterenglish.api.modules.vocabulary.service.impl.VocabularySeedServiceImpl;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * End-to-end M10.07 integration test verifying Spring Modulith outbox persistence and
 * asynchronous @ApplicationModuleListener dispatch after transaction commit.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(
        properties = {
            "notification.mail.enabled=true",
            "notification.mail.from=reminders@example.test",
            "notification.mail.web-base-url=http://localhost:3000/"
        })
class ContentNewNotificationIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    @Autowired private ContentEvents contentEvents;
    @Autowired private ContentAuthoringService authoringService;
    @Autowired private VocabularySeedService seedService;
    @Autowired private ContentNotificationService contentNotificationService;
    @Autowired private NotificationPreferenceRepository preferences;
    @Autowired private NotificationRepository notifications;
    @Autowired private NotificationDeliveryRepository deliveries;
    @Autowired private JdbcTemplate jdbc;

    private final List<UUID> testUsers = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID userId : testUsers) {
            jdbc.update(
                    "DELETE FROM notification_deliveries WHERE notification_id IN (SELECT id FROM notifications WHERE user_id=?)",
                    userId);
            jdbc.update("DELETE FROM notifications WHERE user_id=?", userId);
            jdbc.update("DELETE FROM notification_preferences WHERE user_id=?", userId);
            jdbc.update("DELETE FROM user_roles WHERE user_id=?", userId);
            jdbc.update("DELETE FROM users WHERE id=?", userId);
        }
        testUsers.clear();
    }

    @Test
    @DisplayName(
            "M10.07: Chỉ DECK xuất bản lần đầu qua authoring mới thông báo tới LEARNER hợp lệ đã opt-in; bỏ qua seed, WORD, bản sửa v2+ và admin")
    void publishesContentNewOnlyOnFirstManualDeckPublicationToEligibleLearners() throws Exception {
        UUID admin = createAccount("content-new-admin", "ACTIVE", true, false, 2);
        seedService.seedVocabularyData(admin);

        UUID optedInLearner = createAccount("cn-learner-in", "ACTIVE", true, true, 1);
        UUID optedOutLearner = createAccount("cn-learner-out", "ACTIVE", true, true, 1);
        UUID hybridContentAdmin = createAccount("cn-hybrid-ca", "ACTIVE", true, true, 1, 2);
        UUID hybridSystemAdmin = createAccount("cn-hybrid-sa", "ACTIVE", true, true, 1, 3);
        UUID unverifiedLearner =
                createAccount("cn-unverified", "PENDING_VERIFICATION", false, true, 1);
        UUID disabledLearner = createAccount("cn-disabled", "DISABLED", true, true, 1);

        savePref(optedInLearner, true, true, true);
        savePref(optedOutLearner, true, true, false);
        savePref(hybridContentAdmin, true, true, true);
        savePref(hybridSystemAdmin, true, true, true);
        savePref(unverifiedLearner, true, true, true);
        savePref(disabledLearner, true, true, true);

        // 1. Seed data (importNamespace != null) MUST NOT trigger CONTENT_NEW
        assertThat(notifications.findFeed(optedInLearner, PageRequest.of(0, 10)).getTotalElements())
                .isZero();

        // 2. Manual WORD first publication MUST NOT trigger CONTENT_NEW
        var wordItem =
                contentEvents.createWordDraft(
                        new WordDraft(
                                "clarity-" + UUID.randomUUID().toString().substring(0, 6),
                                "NOUN",
                                "B2",
                                "sự rõ ràng",
                                "quality of being clear",
                                "/ˈklærəti/",
                                List.of("Clarity helps readers."),
                                List.of("Sự rõ ràng giúp người đọc."),
                                VocabularySeedServiceImpl.DEFAULT_SOURCE_ID,
                                "Initial word"),
                        admin);
        var wordRev = contentEvents.submitRevision(latestRevisionId(wordItem.id()), admin);
        contentEvents.approveRevision(wordRev.revisionId(), "ok", admin);
        contentEvents.publishRevision(wordRev.revisionId(), admin);

        // Wait briefly for Modulith listener and confirm still 0 notifications for optedInLearner
        Thread.sleep(300);
        assertThat(notifications.findFeed(optedInLearner, PageRequest.of(0, 10)).getTotalElements())
                .isZero();

        // 3. Manual DECK v1 first publication triggers async DeckFirstPublished -> CONTENT_NEW
        UUID senseId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        var deckItem =
                contentEvents.createDeckDraft(
                        new DeckDraft(
                                "Business English Essentials",
                                "Core phrases for meetings",
                                "B2",
                                List.of(senseId),
                                VocabularySeedServiceImpl.DEFAULT_SOURCE_ID,
                                "Initial deck release"),
                        admin);
        UUID deckRev1Id = latestRevisionId(deckItem.id());
        contentEvents.submitRevision(deckRev1Id, admin);
        contentEvents.approveRevision(deckRev1Id, "approved", admin);
        contentEvents.publishRevision(deckRev1Id, admin);

        awaitFeedCount(optedInLearner, 1L);

        var row =
                jdbc.queryForMap(
                        "SELECT id,type,visibility,title,body,target_url,dedupe_key,reminder_rule_id FROM notifications WHERE user_id=?",
                        optedInLearner);
        assertThat(row.get("type")).isEqualTo("CONTENT_NEW");
        assertThat(row.get("visibility")).isEqualTo("USER");
        assertThat(row.get("title")).isEqualTo("Bộ từ vựng mới: Business English Essentials");
        assertThat((String) row.get("body")).contains("Business English Essentials").contains("B2");
        assertThat(row.get("target_url")).isEqualTo("/learn/vocabulary?deck=" + deckItem.id());
        assertThat(row.get("dedupe_key"))
                .isEqualTo("CONTENT_NEW:" + optedInLearner + ":DECK:" + deckItem.id());
        assertThat(row.get("reminder_rule_id")).isNull();

        UUID notificationId = (UUID) row.get("id");
        assertThat(
                        deliveries.findByNotificationIdAndChannel(
                                notificationId, DeliveryChannel.IN_APP))
                .get()
                .extracting(d -> d.getStatus())
                .isEqualTo(DeliveryStatus.SENT);
        var emailDelivery =
                deliveries
                        .findByNotificationIdAndChannel(notificationId, DeliveryChannel.EMAIL)
                        .orElseThrow();
        assertThat(emailDelivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);

        // Excluded accounts MUST NOT receive notifications
        for (UUID excluded :
                List.of(
                        optedOutLearner,
                        hybridContentAdmin,
                        hybridSystemAdmin,
                        unverifiedLearner,
                        disabledLearner)) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM notifications WHERE user_id=?",
                                    Integer.class,
                                    excluded))
                    .isZero();
        }

        // 4. Publishing v2 revision of same DECK MUST NOT create a second CONTENT_NEW notification
        UUID lateLearner = createAccount("cn-late-learner", "ACTIVE", true, true, 1);
        savePref(lateLearner, true, false, true);

        String v1Snapshot =
                jdbc.queryForObject(
                        "SELECT snapshot::text FROM content_revisions WHERE id=?",
                        String.class,
                        deckRev1Id);
        var v2Draft =
                authoringService.createItemWithDraft(
                        deckItem.id(),
                        "DECK",
                        admin,
                        null,
                        null,
                        v1Snapshot,
                        "Typo fix v2",
                        VocabularySeedServiceImpl.DEFAULT_SOURCE_ID);
        UUID v2RevId = latestRevisionId(v2Draft.getId());
        authoringService.submitForReview(v2RevId, admin);
        authoringService.approve(v2RevId, admin, "v2 approved");
        authoringService.publish(v2RevId, admin);

        Thread.sleep(300);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notifications WHERE user_id=?",
                                Integer.class,
                                lateLearner))
                .isZero();

        // 5. Archiving deck suppresses CONTENT_NEW even if invoked directly
        authoringService.archiveItem(deckItem.id(), "Retracted", admin);
        assertThat(
                        contentNotificationService.notifyDeckFirstPublished(
                                deckItem.id(), deckRev1Id, "Business English Essentials", "B2"))
                .isZero();
    }

    private void awaitFeedCount(UUID userId, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (notifications.findFeed(userId, PageRequest.of(0, 10)).getTotalElements()
                    >= expected) {
                return;
            }
            Thread.sleep(25L);
        }
        assertThat(notifications.findFeed(userId, PageRequest.of(0, 10)).getTotalElements())
                .isGreaterThanOrEqualTo(expected);
    }

    private UUID latestRevisionId(UUID contentItemId) {
        return jdbc.queryForObject(
                "SELECT id FROM content_revisions WHERE content_item_id=? ORDER BY version_no DESC LIMIT 1",
                UUID.class,
                contentItemId);
    }

    private void savePref(UUID userId, boolean inApp, boolean email, boolean contentEnabled) {
        var pref = new NotificationPreference(userId);
        pref.update(
                inApp,
                email,
                contentEnabled,
                LocalTime.of(20, 0),
                "Asia/Ho_Chi_Minh",
                null,
                null,
                NOW);
        preferences.saveAndFlush(pref);
    }

    private UUID createAccount(
            String prefix, String status, boolean verified, boolean trackCleanup, int... roleIds) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, first_name, last_name, status, email_verified_at) "
                        + "VALUES (?, ?, 'fixture-only', 'Content', 'Notify', ?, ?)",
                id,
                prefix + "-" + id + "@example.test",
                status,
                verified ? java.sql.Timestamp.from(NOW) : null);
        for (int roleId : roleIds) {
            jdbc.update("INSERT INTO user_roles(user_id, role_id) VALUES (?, ?)", id, roleId);
        }
        if (trackCleanup) {
            testUsers.add(id);
        }
        return id;
    }
}
