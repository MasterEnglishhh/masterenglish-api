package com.masterenglish.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.common.events.LearningEvents;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.learning.entity.UserWordProgress;
import com.masterenglish.api.modules.learning.repository.UserWordProgressRepository;
import com.masterenglish.api.modules.vocabulary.service.VocabularySeedService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
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
    @Autowired private VocabularySeedService seedService;
    @Autowired private UserWordProgressRepository progress;
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
