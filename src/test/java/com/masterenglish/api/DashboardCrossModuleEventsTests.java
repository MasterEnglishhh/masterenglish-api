package com.masterenglish.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.masterenglish.api.common.events.GrammarEvents;
import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.common.events.LearningEvents;
import com.masterenglish.api.common.events.NotificationEvents;
import com.masterenglish.api.common.events.PracticeEvents;
import com.masterenglish.api.common.events.VocabularyEvents;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** Smoke verifies every cross-module event contract used by the dashboard module. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class DashboardCrossModuleEventsTests {

    @Autowired private IdentityEvents identity;
    @Autowired private LearningEvents learning;
    @Autowired private VocabularyEvents vocabulary;
    @Autowired private GrammarEvents grammar;
    @Autowired private PracticeEvents practice;
    @Autowired private NotificationEvents notifications;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("Mọi hợp đồng sự kiện phục vụ M2 Dashboard phản hồi đồng bộ cho người học mới")
    void allDashboardEventContractsRespondForFreshLearner() {
        User learner =
                new User(
                        "dash-evt-" + UUID.randomUUID() + "@example.com",
                        "fixture-only",
                        "Dash",
                        "Event");
        learner.addRole(entityManager.find(Role.class, (short) 1));
        learner.markEmailVerified(Instant.now());
        learner = userRepository.saveAndFlush(learner);
        UUID userId = learner.getId();

        assertThat(identity.findLearningGoal(userId)).isEmpty();
        assertThat(notifications.findLearnerTimezone(userId)).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(notifications.countUnreadInFeed(userId)).isZero();
        assertThat(learning.getVocabularyProgress(userId).studiedSenses()).isZero();
        assertThat(learning.getLessonProgressSummary(userId).completedCount()).isZero();
        assertThat(learning.countDueCardsByDeck(userId, Instant.now())).isEmpty();
        assertThat(learning.findActivityLocalDays(userId, "Asia/Ho_Chi_Minh")).isEmpty();
        assertThat(learning.getDailyStudySeconds(userId, LocalDate.of(2026, 11, 25))).isZero();
        assertThat(vocabulary.findActiveDecksByIds(Set.of())).isEmpty();
        assertThat(grammar.listVisibleTopics()).isNotNull();
        assertThat(grammar.listVisibleLessons()).isNotNull();
        assertThat(
                        practice.getPracticeSummary(
                                        userId,
                                        Instant.now().minusSeconds(86400),
                                        "Asia/Ho_Chi_Minh")
                                .submittedAttemptCount())
                .isZero();
    }
}
