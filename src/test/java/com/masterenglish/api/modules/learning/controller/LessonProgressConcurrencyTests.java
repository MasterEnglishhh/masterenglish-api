package com.masterenglish.api.modules.learning.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.infrastructure.security.UserPrincipal;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.support.ConcurrentRequests;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Races on lesson progress with real commits. Not @Transactional: each request commits on its own
 * thread, so the database decides the outcome. Every round uses a new learner and lesson.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LessonProgressConcurrencyTests {

    private static final int ROUNDS = 20;

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private User adminOnly;
    private Role learnerRole;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        learnerRole = entityManager.find(Role.class, (short) 1);
        Role adminRole = entityManager.find(Role.class, (short) 2);
        adminOnly = account("admin", adminRole);
    }

    @Test
    void simultaneousCompletionsLeaveOneUnchangedCompletedRecord() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UserPrincipal learner = UserPrincipal.create(account("learner", learnerRole));
            UUID lesson = publishedLesson();

            List<Integer> statuses =
                    ConcurrentRequests.runAtOnce(2, () -> send(learner, lesson, "COMPLETED"));

            assertThat(statuses).containsOnly(200);
            Map<String, Object> row = row(learner, lesson);
            assertThat(row.get("state")).isEqualTo("COMPLETED");
            // Only the first completion writes; the second finds the row already completed.
            assertThat(row.get("version")).isEqualTo(0L);
            assertThat(row.get("completed_at")).isNotNull();
            assertThat(row.get("last_completed_revision_id")).isEqualTo(currentRevision(lesson));
        }
    }

    @Test
    void startingAndCompletingTogetherNeverDowngradesACompletedRecord() throws Exception {
        String[] states = {"IN_PROGRESS", "COMPLETED"};
        for (int round = 0; round < ROUNDS; round++) {
            UserPrincipal learner = UserPrincipal.create(account("learner", learnerRole));
            UUID lesson = publishedLesson();
            AtomicInteger next = new AtomicInteger();

            List<Integer> statuses =
                    ConcurrentRequests.runAtOnce(
                            2, () -> send(learner, lesson, states[next.getAndIncrement()]));

            assertThat(statuses).containsOnly(200);
            assertThat(row(learner, lesson).get("state")).isEqualTo("COMPLETED");
        }
    }

    private int send(UserPrincipal principal, UUID lesson, String state) throws Exception {
        return mockMvc.perform(
                        put("/api/v1/grammar/lessons/" + lesson + "/progress")
                                .with(user(principal))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"state\":\"" + state + "\"}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private Map<String, Object> row(UserPrincipal learner, UUID lesson) {
        return jdbc.queryForMap(
                "SELECT state, version, completed_at, last_completed_revision_id"
                        + " FROM lesson_progress WHERE user_id = ? AND lesson_id = ?",
                learner.getId(),
                lesson);
    }

    private User account(String prefix, Role role) {
        User account =
                new User(
                        prefix + "-" + suffix() + "@example.com",
                        passwordEncoder.encode("Password123!@#"),
                        "Concurrent",
                        prefix);
        account.addRole(role);
        account.markEmailVerified(Instant.now());
        return userRepository.saveAndFlush(account);
    }

    /** Publishes a lesson with its topic, committed, so the request can read it. */
    private UUID publishedLesson() {
        UUID topic = publishItem("GRAMMAR_TOPIC");
        jdbc.update(
                "INSERT INTO grammar_topics (id, published_revision_id, slug, name, difficulty, sort_order)"
                        + " VALUES (?, ?, ?, ?, 'BASIC', ?)",
                topic,
                currentRevision(topic),
                "topic-" + suffix(),
                "Topic " + suffix(),
                1);
        UUID lesson = publishItem("GRAMMAR_LESSON");
        jdbc.update(
                "INSERT INTO grammar_lessons (id, published_revision_id, topic_id, title, body, sort_order)"
                        + " VALUES (?, ?, ?, ?, ?::jsonb, ?)",
                lesson,
                currentRevision(lesson),
                topic,
                "Lesson " + suffix(),
                "{\"blocks\":[]}",
                1);
        return lesson;
    }

    private UUID publishItem(String type) {
        UUID item = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO content_items (id, content_type, created_by) VALUES (?, ?, ?)",
                item,
                type,
                adminOnly.getId());
        jdbc.update(
                "INSERT INTO content_revisions (id, content_item_id, version_no, snapshot, status, created_by)"
                        + " VALUES (?, ?, 1, '{}'::jsonb, 'DRAFT', ?)",
                revision,
                item,
                adminOnly.getId());
        jdbc.update(
                "UPDATE content_items SET published_revision_id = ? WHERE id = ?", revision, item);
        jdbc.update(
                "UPDATE content_revisions SET status = 'PUBLISHED', reviewed_by = ?, reviewed_at = now(),"
                        + " published_at = now() WHERE id = ?",
                adminOnly.getId(),
                revision);
        return item;
    }

    private UUID currentRevision(UUID item) {
        return jdbc.queryForObject(
                "SELECT published_revision_id FROM content_items WHERE id = ?", UUID.class, item);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
