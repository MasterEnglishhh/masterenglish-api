package com.masterenglish.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.learning.dto.ReviewRequest;
import com.masterenglish.api.modules.learning.dto.ReviewResponse;
import com.masterenglish.api.modules.learning.repository.UserWordProgressRepository;
import com.masterenglish.api.modules.learning.service.ReviewService;
import com.masterenglish.api.modules.vocabulary.query.VocabularyQueryService;
import com.masterenglish.api.modules.vocabulary.query.WordSenseCardDto;
import com.masterenglish.api.modules.vocabulary.seed.VocabularySeedService;
import com.masterenglish.api.modules.vocabulary.seed.VocabularySeedServiceImpl;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Regression coverage for review consistency, immutable history and pinned cards. */
@SpringBootTest(properties = "test.vocabulary.regression-isolation=true")
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Import(TestcontainersConfiguration.class)
@Transactional
class VocabularyRegressionTests {
    @Autowired UserRepository users;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired VocabularySeedService seed;
    @Autowired VocabularyQueryService vocabulary;
    @Autowired ReviewService reviews;
    @Autowired UserWordProgressRepository progress;
    @Autowired com.masterenglish.api.modules.learning.service.DueQueueService dueQueue;
    @Autowired com.masterenglish.api.modules.learning.service.SavedWordService saved;
    @Autowired com.masterenglish.api.modules.content.authoring.ContentAuthoringService authoring;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    User admin;
    User learner;
    WordSenseCardDto card;

    @BeforeEach
    void fixtures() {
        learner = user("audit-learner@example.test", (short) 1);
        admin = user("audit-admin@example.test", (short) 2);
        seed.seedVocabularyData(admin.getId());
        card =
                vocabulary
                        .getCardBySenseId(UUID.fromString("20000000-0000-0000-0000-000000000001"))
                        .orElseThrow();
    }

    private User user(String email, short role) {
        User user =
                new User(
                        UUID.randomUUID() + email,
                        "fixture-only-not-a-login-password",
                        "Audit",
                        "Fixture");
        user.markEmailVerified(Instant.now());
        user.addRole(em.find(Role.class, role));
        return users.saveAndFlush(user);
    }

    private ReviewRequest request(UUID requestId, Long version) {
        return new ReviewRequest(
                card.wordSenseId(),
                card.wordId(),
                card.wordRevisionId(),
                null,
                null,
                "GOOD",
                requestId,
                version);
    }

    @Test
    void anotherFirstReviewWithNullVersionMustConflict() {
        reviews.reviewCard(learner.getId(), request(UUID.randomUUID(), null));
        assertThatThrownBy(
                        () -> reviews.reviewCard(learner.getId(), request(UUID.randomUUID(), null)))
                .as("a stale first-study card must not bypass the existing progress version")
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void reviewBeforeDueMustNotAdvanceSchedule() {
        ReviewResponse first =
                reviews.reviewCard(learner.getId(), request(UUID.randomUUID(), null));
        assertThatThrownBy(
                        () ->
                                reviews.reviewCard(
                                        learner.getId(),
                                        request(UUID.randomUUID(), first.progressVersion())))
                .as("a card due tomorrow must not advance to six days immediately")
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void retryMustReturnTheOriginalStoredStateAfterProgressChanges() {
        ReviewRequest request = request(UUID.randomUUID(), null);
        ReviewResponse first = reviews.reviewCard(learner.getId(), request);
        var changed =
                progress.findByIdUserIdAndIdWordSenseId(learner.getId(), card.wordSenseId())
                        .orElseThrow();
        changed.updateProgress(
                3,
                17,
                new BigDecimal("2.800"),
                first.nextDueAt().plusSeconds(86400 * 20L),
                Instant.now());
        progress.saveAndFlush(changed);
        ReviewResponse replay = reviews.reviewCard(learner.getId(), request);
        assertThat(replay.intervalDays())
                .as("retry must use word_reviews.after_state, not current progress")
                .isEqualTo(first.intervalDays());
        assertThat(replay.repetitions()).isEqualTo(first.repetitions());
        assertThat(replay.progressVersion()).isEqualTo(first.progressVersion());
    }

    @Test
    void aDeckMustKeepItsPinnedRevisionWhenWordProjectionChanges() {
        var before =
                vocabulary.getDeckCards(VocabularySeedServiceImpl.DECK_1_ID).stream()
                        .filter(c -> c.wordSenseId().equals(card.wordSenseId()))
                        .findFirst()
                        .orElseThrow();
        em.flush();
        UUID nextRevision = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO content_revisions
                (id, content_item_id, version_no, schema_version, snapshot, status, created_by,
                 reviewed_by, reviewed_at, published_at)
                SELECT ?, content_item_id, version_no+1, schema_version,
                  jsonb_set(snapshot, '{senses,0,meaning_vi}', to_jsonb('changed published meaning'::text)),
                  'PUBLISHED', created_by, reviewed_by, NOW(), NOW()
                FROM content_revisions WHERE id=?
                """,
                nextRevision,
                card.wordRevisionId());
        jdbc.update(
                "UPDATE word_senses SET word_revision_id=?, meaning_vi='changed published meaning' WHERE id=?",
                nextRevision,
                card.wordSenseId());
        jdbc.update(
                "UPDATE words SET published_revision_id=? WHERE id=?", nextRevision, card.wordId());
        jdbc.update(
                "UPDATE content_items SET published_revision_id=? WHERE id=?",
                nextRevision,
                card.wordId());
        em.clear();
        var after =
                vocabulary.getDeckCards(VocabularySeedServiceImpl.DECK_1_ID).stream()
                        .filter(c -> c.wordSenseId().equals(card.wordSenseId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(after.wordRevisionId())
                .as("the unchanged deck must keep its original word revision")
                .isEqualTo(before.wordRevisionId());
        assertThat(after.meaningVi()).isEqualTo(before.meaningVi());
    }

    @Test
    void reviewLogMustRejectInPlaceMutation() {
        var review = reviews.reviewCard(learner.getId(), request(UUID.randomUUID(), null));
        em.flush();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE word_reviews SET rating='HARD' WHERE id=?",
                                        review.reviewId()))
                .as("the official migration must enforce append-only review history")
                .isInstanceOf(DataAccessException.class);
    }

    private java.util.List<WordSenseCardDto> allCards() {
        return java.util.stream.Stream.concat(
                        vocabulary.getDeckCards(VocabularySeedServiceImpl.DECK_1_ID).stream(),
                        vocabulary.getDeckCards(VocabularySeedServiceImpl.DECK_2_ID).stream())
                .distinct()
                .toList();
    }

    @Test
    void dueQueueFiltersHiddenContentBeforeLimitAndCount() {
        var other =
                allCards().stream()
                        .filter(c -> !c.wordId().equals(card.wordId()))
                        .findFirst()
                        .orElseThrow();
        Instant now = Instant.now();
        progress.saveAndFlush(
                new com.masterenglish.api.modules.learning.entity.UserWordProgress(
                        learner.getId(),
                        card.wordSenseId(),
                        1,
                        1,
                        new BigDecimal("2.600"),
                        now.minusSeconds(200),
                        now,
                        "SM2_3BTN_V1"));
        progress.saveAndFlush(
                new com.masterenglish.api.modules.learning.entity.UserWordProgress(
                        learner.getId(),
                        other.wordSenseId(),
                        1,
                        1,
                        new BigDecimal("2.600"),
                        now.minusSeconds(100),
                        now,
                        "SM2_3BTN_V1"));
        jdbc.update(
                "UPDATE content_items SET is_archived=true,archived_at=NOW() WHERE id=?",
                card.wordId());
        em.clear();
        var queue = dueQueue.getDueQueue(learner.getId(), null, 1);
        assertThat(queue.totalDue()).isEqualTo(1);
        assertThat(queue.cards())
                .extracting(WordSenseCardDto::wordSenseId)
                .containsExactly(other.wordSenseId());
    }

    @Test
    void savedFilterAndCountAreAppliedBeforePagination() {
        var target =
                allCards().stream()
                        .filter(c -> !c.cefrLevel().equals(card.cefrLevel()))
                        .findFirst()
                        .orElseThrow();
        saved.saveWord(learner.getId(), card.wordSenseId());
        saved.saveWord(learner.getId(), target.wordSenseId());
        jdbc.update(
                "UPDATE user_saved_words SET saved_at=NOW()-interval '1 day' WHERE user_id=? AND word_sense_id=?",
                learner.getId(),
                target.wordSenseId());
        var result =
                saved.getSavedWords(
                        learner.getId(),
                        target.cefrLevel(),
                        org.springframework.data.domain.PageRequest.of(0, 1));
        assertThat(result.totalItems()).isEqualTo(1);
        assertThat(result.items())
                .extracting(com.masterenglish.api.modules.learning.dto.SavedWordDto::wordSenseId)
                .containsExactly(target.wordSenseId());
    }

    @Test
    void publishedSnapshotsRejectMutation() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE content_revisions SET snapshot=snapshot || '{\"tampered\":true}'::jsonb WHERE id=?",
                                        card.wordRevisionId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void auditLogRejectsMutation() {
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_logs SET reason='tampered'"))
                .isInstanceOf(DataAccessException.class);
    }

    private UUID draft(UUID itemId, String type, String snapshot, UUID source) {
        var item =
                authoring.createItemWithDraft(
                        itemId,
                        type,
                        admin.getId(),
                        null,
                        null,
                        snapshot,
                        "Regression draft",
                        source);
        em.flush();
        return jdbc.queryForObject(
                "SELECT id FROM content_revisions WHERE content_item_id=? AND status='DRAFT'",
                UUID.class,
                item.getId());
    }

    private void approve(UUID revision) {
        authoring.submitForReview(revision, admin.getId());
        authoring.approve(revision, admin.getId(), "Checked");
    }

    @Test
    void draftCannotBePublishedWithoutReview() {
        String snapshot =
                jdbc.queryForObject(
                        "SELECT snapshot::text FROM content_revisions WHERE id=?",
                        String.class,
                        card.wordRevisionId());
        UUID revision =
                draft(card.wordId(), "WORD", snapshot, VocabularySeedServiceImpl.DEFAULT_SOURCE_ID);
        assertThatThrownBy(() -> authoring.publish(revision, admin.getId()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void learnerCannotAuthorContentBySupplyingTheirOwnId() {
        assertThatThrownBy(
                        () ->
                                authoring.createSource(
                                        UUID.randomUUID(),
                                        "source",
                                        "SELF_AUTHORED",
                                        null,
                                        null,
                                        "TEST",
                                        null,
                                        null,
                                        "original",
                                        learner.getId()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void approvedWordWithoutSourceCannotBePublished() {
        String snapshot =
                jdbc.queryForObject(
                        "SELECT snapshot::text FROM content_revisions WHERE id=?",
                        String.class,
                        card.wordRevisionId());
        UUID revision = draft(card.wordId(), "WORD", snapshot, null);
        approve(revision);
        assertThatThrownBy(() -> authoring.publish(revision, admin.getId()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deckCannotPinAnotherDeckAsAWord() throws Exception {
        var deck = vocabulary.getDeckDetail(VocabularySeedServiceImpl.DECK_1_ID);
        var snapshot =
                mapper.readTree(
                        jdbc.queryForObject(
                                "SELECT snapshot::text FROM content_revisions WHERE id=?",
                                String.class,
                                deck.publishedRevisionId()));
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        snapshot.path("data").path("members").get(0))
                .put(
                        "pinned_word_revision_id",
                        vocabulary
                                .getDeckDetail(VocabularySeedServiceImpl.DECK_2_ID)
                                .publishedRevisionId()
                                .toString());
        UUID revision =
                draft(
                        deck.id(),
                        "DECK",
                        snapshot.toString(),
                        VocabularySeedServiceImpl.DEFAULT_SOURCE_ID);
        approve(revision);
        assertThatThrownBy(() -> authoring.publish(revision, admin.getId()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deckReviewUsesThePinnedContextAndRejectsWrongDeckRevision() {
        var pinned = vocabulary.getDeckCards(VocabularySeedServiceImpl.DECK_1_ID).get(0);
        var request =
                new ReviewRequest(
                        pinned.wordSenseId(),
                        pinned.wordId(),
                        pinned.wordRevisionId(),
                        pinned.deckId(),
                        pinned.deckRevisionId(),
                        "GOOD",
                        UUID.randomUUID(),
                        null);
        assertThat(reviews.reviewCard(learner.getId(), request).intervalDays()).isEqualTo(1);
        var forged =
                new ReviewRequest(
                        pinned.wordSenseId(),
                        pinned.wordId(),
                        pinned.wordRevisionId(),
                        pinned.deckId(),
                        UUID.randomUUID(),
                        "GOOD",
                        UUID.randomUUID(),
                        null);
        assertThatThrownBy(() -> reviews.reviewCard(learner.getId(), forged))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void twoTabsCannotBothCreateFirstProgress() throws Exception {
        UUID userId = learner.getId();
        ReviewRequest first = request(UUID.randomUUID(), null),
                second = request(UUID.randomUUID(), null);
        org.springframework.test.context.transaction.TestTransaction.flagForCommit();
        org.springframework.test.context.transaction.TestTransaction.end();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.List<java.util.concurrent.Future<Boolean>> outcomes =
                    new java.util.ArrayList<>();
            for (var payload : java.util.List.of(first, second))
                outcomes.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    try {
                                        reviews.reviewCard(userId, payload);
                                        return true;
                                    } catch (BusinessException expected) {
                                        assertThat(expected.getErrorCode())
                                                .isEqualTo(
                                                        com.masterenglish.api.common.error.ErrorCode
                                                                .CONFLICT);
                                        return false;
                                    }
                                }));
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int successes = 0;
            for (var result : outcomes)
                if (result.get(20, java.util.concurrent.TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
            assertThat(
                            progress.findByIdUserIdAndIdWordSenseId(userId, card.wordSenseId())
                                    .orElseThrow()
                                    .getRepetitions())
                    .isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            org.springframework.test.context.transaction.TestTransaction.start();
        }
    }

    @Test
    void publishingANewWordRevisionKeepsTheExistingDeckAndExamplesFrozen() throws Exception {
        var before =
                vocabulary.getDeckCards(VocabularySeedServiceImpl.DECK_1_ID).stream()
                        .filter(c -> c.wordSenseId().equals(card.wordSenseId()))
                        .findFirst()
                        .orElseThrow();
        var snapshot =
                mapper.readTree(
                        jdbc.queryForObject(
                                "SELECT snapshot::text FROM content_revisions WHERE id=?",
                                String.class,
                                card.wordRevisionId()));
        var sense =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        snapshot.path("data").path("senses").get(0);
        sense.put("meaning_vi", "Updated meaning for current library");
        var example =
                (com.fasterxml.jackson.databind.node.ObjectNode) sense.path("examples").get(0);
        example.put("id", UUID.randomUUID().toString());
        example.put("example_en", "An updated original example.");
        UUID next =
                draft(
                        card.wordId(),
                        "WORD",
                        snapshot.toString(),
                        VocabularySeedServiceImpl.DEFAULT_SOURCE_ID);
        approve(next);
        authoring.publish(next, admin.getId());
        em.flush();
        em.clear();
        var current = vocabulary.getCardBySenseId(card.wordSenseId()).orElseThrow();
        assertThat(current.wordRevisionId()).isEqualTo(next);
        assertThat(current.meaningVi()).isEqualTo("Updated meaning for current library");
        assertThat(current.examples()).hasSize(1);
        var pinned =
                vocabulary.getDeckCards(VocabularySeedServiceImpl.DECK_1_ID).stream()
                        .filter(c -> c.wordSenseId().equals(card.wordSenseId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(pinned.wordRevisionId()).isEqualTo(before.wordRevisionId());
        assertThat(pinned.meaningVi()).isEqualTo(before.meaningVi());
        assertThat(pinned.examples()).isEqualTo(before.examples());
    }

    @Test
    void publishedPayloadRejectsObjectsInOptionalTextFields() throws Exception {
        var snapshot =
                mapper.readTree(
                        jdbc.queryForObject(
                                "SELECT snapshot::text FROM content_revisions WHERE id=?",
                                String.class,
                                card.wordRevisionId()));
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        snapshot.path("data").path("senses").get(0))
                .putObject("definition_en")
                .put("unexpected", "nested value");
        UUID revision =
                draft(
                        card.wordId(),
                        "WORD",
                        snapshot.toString(),
                        VocabularySeedServiceImpl.DEFAULT_SOURCE_ID);
        approve(revision);
        assertThatThrownBy(() -> authoring.publish(revision, admin.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        error ->
                                assertThat(((BusinessException) error).getErrorCode())
                                        .isEqualTo(
                                                com.masterenglish.api.common.error.ErrorCode
                                                        .VALIDATION_ERROR));
    }
}
