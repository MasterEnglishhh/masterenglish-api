package com.masterenglish.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.common.events.IdentityEvents.DailyGoalTarget;
import com.masterenglish.api.common.events.LearningEvents;
import com.masterenglish.api.common.events.PracticeEvents;
import com.masterenglish.api.modules.identity.entity.LearningGoal;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.LearningGoalRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.learning.entity.StudySession;
import com.masterenglish.api.modules.learning.entity.StudySourceType;
import com.masterenglish.api.modules.learning.entity.UserWordProgress;
import com.masterenglish.api.modules.learning.repository.StudySessionRepository;
import com.masterenglish.api.modules.learning.repository.UserWordProgressRepository;
import com.masterenglish.api.modules.vocabulary.service.VocabularySeedService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** The two request/reply contracts that the notifications module uses. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class NotificationCrossModuleEventsTests {

    private static final Instant NOW = Instant.parse("2026-11-19T13:00:00Z");

    @Autowired private LearningEvents learning;
    @Autowired private IdentityEvents identity;
    @Autowired private PracticeEvents practice;
    @Autowired private VocabularySeedService seedService;
    @Autowired private UserWordProgressRepository progress;
    @Autowired private StudySessionRepository studySessions;
    @Autowired private LearningGoalRepository learningGoals;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName(
            "Learning đếm thẻ đến hạn theo từng người học, bỏ thẻ chưa đến hạn và người không có thẻ")
    void countsDueCardsPerLearner() {
        User admin = account("seed-admin", (short) 2);
        seedService.seedVocabularyData(admin.getId());
        UUID first = account("due-first", (short) 1).getId();
        UUID second = account("due-second", (short) 1).getId();
        UUID none = account("due-none", (short) 1).getId();
        progress(first, sense(1), NOW.minusSeconds(3600));
        progress(first, sense(2), NOW);
        progress(first, sense(3), NOW.plusSeconds(60));
        progress(second, sense(1), NOW.minusSeconds(86_400));

        Map<UUID, Long> counts = learning.countDueCards(Set.of(first, second, none), NOW);

        assertThat(counts).containsOnly(Map.entry(first, 2L), Map.entry(second, 1L));
        assertThat(learning.countDueCards(Set.of(), NOW)).isEmpty();
    }

    @Test
    @DisplayName("Identity chỉ trả email của tài khoản ACTIVE")
    void returnsEmailsOfActiveAccountsOnly() {
        UUID active = UUID.randomUUID();
        UUID disabled = UUID.randomUUID();
        insertUser(active, "ACTIVE");
        insertUser(disabled, "DISABLED");

        Map<UUID, String> emails =
                identity.findActiveEmails(Set.of(active, disabled, UUID.randomUUID()));

        assertThat(emails).containsOnly(Map.entry(active, active + "@example.test"));
        assertThat(identity.findActiveEmails(Set.of())).isEmpty();
    }

    @Test
    @DisplayName(
            "findActiveLearnerEmails chỉ trả LEARNER ACTIVE đã xác minh và loại trừ CONTENT_ADMIN / SYSTEM_ADMIN")
    void findActiveLearnerEmailsFiltersVerifiedLearnersWithoutAdminRoles() {
        User pureLearner = account("pure-learner", (short) 1);
        User hybridContentAdmin = account("hybrid-ca", (short) 1);
        hybridContentAdmin.addRole(entityManager.find(Role.class, (short) 2));
        userRepository.saveAndFlush(hybridContentAdmin);

        User hybridSystemAdmin = account("hybrid-sa", (short) 1);
        hybridSystemAdmin.addRole(entityManager.find(Role.class, (short) 3));
        userRepository.saveAndFlush(hybridSystemAdmin);

        User adminOnly = account("admin-only", (short) 2);
        User unverifiedLearner =
                new User(
                        "unverified-" + UUID.randomUUID() + "@example.com",
                        "fixture-only",
                        "Event",
                        "Fixture");
        unverifiedLearner.addRole(entityManager.find(Role.class, (short) 1));
        userRepository.saveAndFlush(unverifiedLearner);

        Map<UUID, String> recipients =
                identity.findActiveLearnerEmails(
                        Set.of(
                                pureLearner.getId(),
                                hybridContentAdmin.getId(),
                                hybridSystemAdmin.getId(),
                                adminOnly.getId(),
                                unverifiedLearner.getId()));

        assertThat(recipients).containsOnly(Map.entry(pureLearner.getId(), pureLearner.getEmail()));
    }

    @Test
    @DisplayName(
            "Identity trả mục tiêu daily_minutes và Learning tổng hợp giây học theo từng local_day")
    void returnsDailyGoalTargetsAndDailyStudySeconds() {
        User admin = account("study-admin", (short) 2);
        seedService.seedVocabularyData(admin.getId());

        User withGoal = account("goal-user", (short) 1);
        User withoutGoal = account("no-goal-user", (short) 1);
        learningGoals.saveAndFlush(new LearningGoal(withGoal.getId(), (short) 700, 30, null));

        LocalDate today = LocalDate.of(2026, 11, 19);
        StudySession session =
                StudySession.open(
                        withGoal.getId(),
                        StudySourceType.FLASHCARD,
                        sense(1),
                        null,
                        null,
                        "Asia/Ho_Chi_Minh",
                        today,
                        UUID.randomUUID(),
                        NOW.minusSeconds(120));
        session.recordHeartbeat(NOW.minusSeconds(90), new BigDecimal("25.500"));
        studySessions.saveAndFlush(session);

        Map<UUID, DailyGoalTarget> targets =
                identity.findDailyGoalTargets(Set.of(withGoal.getId(), withoutGoal.getId()));
        assertThat(targets)
                .containsOnly(
                        Map.entry(withGoal.getId(), new DailyGoalTarget(withGoal.getEmail(), 30)));

        Map<UUID, Long> studySeconds =
                learning.sumDailyStudySeconds(Map.of(withGoal.getId(), today));
        assertThat(studySeconds).containsOnly(Map.entry(withGoal.getId(), 25L));
        assertThat(learning.getDailyStudySeconds(withGoal.getId(), today)).isEqualTo(25L);
        assertThat(practice.existsOwnedAttempt(withGoal.getId(), UUID.randomUUID())).isFalse();
    }

    private static UUID sense(int number) {
        return UUID.fromString("20000000-0000-0000-0000-00000000000" + number);
    }

    private void progress(UUID user, UUID sense, Instant dueAt) {
        progress.saveAndFlush(
                new UserWordProgress(
                        user,
                        sense,
                        1,
                        1,
                        new BigDecimal("2.500"),
                        dueAt,
                        NOW.minusSeconds(86_400),
                        null));
    }

    private User account(String prefix, short roleId) {
        User user =
                new User(
                        prefix + "-" + UUID.randomUUID() + "@example.com",
                        "fixture-only",
                        "Event",
                        "Fixture");
        user.addRole(entityManager.find(Role.class, roleId));
        user.markEmailVerified(Instant.now());
        return userRepository.saveAndFlush(user);
    }

    private void insertUser(UUID id, String status) {
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, first_name, last_name, status, email_verified_at) VALUES (?, ?, 'fixture-only', 'Event', 'Fixture', ?, NOW())",
                id,
                id + "@example.test",
                status);
    }
}
