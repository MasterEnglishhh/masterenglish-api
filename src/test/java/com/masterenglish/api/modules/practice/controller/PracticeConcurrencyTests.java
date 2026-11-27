package com.masterenglish.api.modules.practice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.infrastructure.security.UserPrincipal;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.support.ConcurrentRequests;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Races on the practice engine with real commits (checklist B4, practice/CONTRACT.md bước 5). Not
 * {@code @Transactional}: every request commits on its own thread, so PostgreSQL decides the
 * outcome. Every round uses a new learner and a new topic, so rounds do not share state.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PracticeConcurrencyTests {

    private static final int ROUNDS = 10;
    private static final String PASSWORD = "Password123!@#";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mockMvc;
    private Role learnerRole;
    private User author;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        learnerRole = entityManager.find(Role.class, (short) 1);
        Role adminRole = entityManager.find(Role.class, (short) 2);
        author = account("author", adminRole);
    }

    /** Two autosaves with the same expectedVersion: exactly one wins, the other is stale. */
    @Test
    void simultaneousAutosavesWithTheSameVersionLeaveOneWinner() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UserPrincipal learner = UserPrincipal.create(account("learner", learnerRole));
            Seeded seed = seedTopic(2);
            Started started = start(learner, seed.topic(), 2, UUID.randomUUID());
            UUID snapshot = started.snapshots().get(0);
            UUID right = optionsOf(snapshot)[0];
            UUID wrong = optionsOf(snapshot)[1];
            AtomicInteger next = new AtomicInteger();

            List<Integer> statuses =
                    ConcurrentRequests.runAtOnce(
                            2,
                            () ->
                                    answer(
                                            learner,
                                            started.attemptId(),
                                            snapshot,
                                            next.getAndIncrement() == 0 ? right : wrong,
                                            0L));

            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(answerVersion(snapshot)).isEqualTo(1L);
            assertThat(selectedOption(snapshot)).isIn(right, wrong);
        }
    }

    /** A stale autosave that arrives after a newer write is rejected, not applied. */
    @Test
    void staleAutosaveArrivingLateIsRejected() throws Exception {
        UserPrincipal learner = UserPrincipal.create(account("learner", learnerRole));
        Seeded seed = seedTopic(2);
        Started started = start(learner, seed.topic(), 2, UUID.randomUUID());
        UUID snapshot = started.snapshots().get(0);
        UUID right = optionsOf(snapshot)[0];
        UUID wrong = optionsOf(snapshot)[1];

        assertThat(answer(learner, started.attemptId(), snapshot, right, 0L)).isEqualTo(200);
        assertThat(answer(learner, started.attemptId(), snapshot, wrong, 0L)).isEqualTo(409);
        assertThat(answerVersion(snapshot)).isEqualTo(1L);
        assertThat(selectedOption(snapshot)).isEqualTo(right);
    }

    /**
     * An autosave racing a submit either commits before the submit (and is graded) or is refused
     * after it. The stored score always matches the stored answers.
     */
    @Test
    void autosaveRacingSubmitLeavesAConsistentAttempt() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UserPrincipal learner = UserPrincipal.create(account("learner", learnerRole));
            Seeded seed = seedTopic(2);
            Started started = start(learner, seed.topic(), 2, UUID.randomUUID());
            UUID snapshot = started.snapshots().get(0);
            UUID right = optionsOf(snapshot)[0];
            AtomicInteger next = new AtomicInteger();

            List<String> outcomes =
                    ConcurrentRequests.runAtOnce(
                            2,
                            () -> {
                                if (next.getAndIncrement() == 0) {
                                    return "answer:"
                                            + answer(
                                                    learner,
                                                    started.attemptId(),
                                                    snapshot,
                                                    right,
                                                    0L);
                                }
                                return "submit:" + submit(learner, started.attemptId());
                            });

            assertThat(outcomes).contains("submit:200");
            String answerOutcome =
                    outcomes.stream()
                            .filter(o -> o.startsWith("answer:"))
                            .findFirst()
                            .orElseThrow();
            assertThat(attemptStatus(started.attemptId())).isEqualTo("SUBMITTED");
            long correctAnswers =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM attempt_answers WHERE attempt_id = ?"
                                    + " AND is_correct",
                            Long.class,
                            started.attemptId());
            assertThat(attemptCorrectCount(started.attemptId())).isEqualTo(correctAnswers);
            if (answerOutcome.equals("answer:200")) {
                // The autosave committed before the submit, so the submit graded it.
                assertThat(correctAnswers).isEqualTo(1L);
            }
        }
    }

    /** Two starts with the same Idempotency-Key and body: one attempt, one 201 and one 200. */
    @Test
    void twoStartsWithTheSameKeyCreateOneAttempt() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UserPrincipal learner = UserPrincipal.create(account("learner", learnerRole));
            Seeded seed = seedTopic(2);
            UUID key = UUID.randomUUID();

            List<Integer> statuses =
                    ConcurrentRequests.runAtOnce(
                            2, () -> startOutcome(learner, seed.topic(), 2, key).status());

            assertThat(statuses).containsExactlyInAnyOrder(201, 200);
            assertThat(countAttempts(learner)).isEqualTo(1L);
        }
    }

    /**
     * With four open attempts, two concurrent starts: one takes the fifth slot, the other is
     * refused with OPEN_ATTEMPT_LIMIT_REACHED. The user lock serializes the count check.
     */
    @Test
    void concurrentStartsAtTheOpenLimitLeaveOneRejected() throws Exception {
        for (int round = 0; round < 5; round++) {
            UserPrincipal learner = UserPrincipal.create(account("learner", learnerRole));
            Seeded seed = seedTopic(2);
            for (int open = 0; open < 4; open++) {
                assertThat(startOutcome(learner, seed.topic(), 2, UUID.randomUUID()).status())
                        .isEqualTo(201);
            }

            List<String> outcomes =
                    ConcurrentRequests.runAtOnce(
                            2,
                            () -> {
                                Outcome outcome =
                                        startOutcome(learner, seed.topic(), 2, UUID.randomUUID());
                                return outcome.status() == 201
                                        ? "201"
                                        : outcome.status() + " " + outcome.code();
                            });

            assertThat(outcomes).containsExactlyInAnyOrder("201", "409 OPEN_ATTEMPT_LIMIT_REACHED");
            assertThat(countOpenAttempts(learner)).isEqualTo(5L);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** The correct and the wrong option of the question sealed in this snapshot. */
    private UUID[] optionsOf(UUID snapshot) {
        UUID right =
                jdbc.queryForObject(
                        "SELECT o.id FROM question_options o"
                                + " JOIN attempt_question_snapshots s ON s.question_id = o.question_id"
                                + " WHERE s.id = ? AND o.is_correct",
                        UUID.class,
                        snapshot);
        UUID wrong =
                jdbc.queryForObject(
                        "SELECT o.id FROM question_options o"
                                + " JOIN attempt_question_snapshots s ON s.question_id = o.question_id"
                                + " WHERE s.id = ? AND NOT o.is_correct",
                        UUID.class,
                        snapshot);
        return new UUID[] {right, wrong};
    }

    private record Question(UUID id, UUID correct, UUID wrong) {}

    private record Seeded(UUID topic, List<Question> questions) {}

    private record Started(UUID attemptId, List<UUID> snapshots) {}

    private record Outcome(int status, String code) {}

    /**
     * Publishes a grammar topic with {@code count} single-choice questions. Runs in autocommit, so
     * the item is inserted with no revision first, then linked once its revision exists.
     */
    private Seeded seedTopic(int count) {
        UUID topic = UUID.randomUUID();
        UUID topicRev = UUID.randomUUID();
        insertItem(topic, "GRAMMAR_TOPIC");
        insertRevision(topicRev, topic);
        linkRevision(topic, topicRev);
        jdbc.update(
                "INSERT INTO grammar_topics (id, published_revision_id, slug, name, difficulty,"
                        + " sort_order) VALUES (?, ?, ?, 'Concurrency', 'BASIC', 0)",
                topic,
                topicRev,
                "conc-" + suffix());
        List<Question> questions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID question = UUID.randomUUID();
            UUID rev = UUID.randomUUID();
            UUID right = UUID.randomUUID();
            UUID wrong = UUID.randomUUID();
            insertItem(question, "QUESTION");
            insertRevision(rev, question);
            linkRevision(question, rev);
            jdbc.update(
                    "INSERT INTO questions (id, published_revision_id, category, prompt,"
                            + " explanation) VALUES (?, ?, 'GRAMMAR', 'Pick', 'Because')",
                    question,
                    rev);
            jdbc.update(
                    "INSERT INTO question_options (id, question_id, position, body, is_correct)"
                            + " VALUES (?, ?, 1, 'right', TRUE), (?, ?, 2, 'wrong', FALSE)",
                    right,
                    question,
                    wrong,
                    question);
            jdbc.update(
                    "INSERT INTO question_topics (question_id, topic_id) VALUES (?, ?)",
                    question,
                    topic);
            questions.add(new Question(question, right, wrong));
        }
        return new Seeded(topic, questions);
    }

    private void insertItem(UUID id, String type) {
        jdbc.update(
                "INSERT INTO content_items (id, content_type, created_by) VALUES (?, ?, ?)",
                id,
                type,
                author.getId());
    }

    private void insertRevision(UUID revision, UUID item) {
        jdbc.update(
                "INSERT INTO content_revisions (id, content_item_id, version_no, status,"
                        + " created_by, published_at, reviewed_by, reviewed_at)"
                        + " VALUES (?, ?, 1, 'PUBLISHED', ?, now(), ?, now())",
                revision,
                item,
                author.getId(),
                author.getId());
    }

    private void linkRevision(UUID item, UUID revision) {
        jdbc.update(
                "UPDATE content_items SET published_revision_id = ? WHERE id = ?", revision, item);
    }

    private Started start(UserPrincipal learner, UUID topic, int count, UUID key) throws Exception {
        var response = mockMvc.perform(startRequest(learner, topic, count, key)).andReturn();
        assertThat(response.getResponse().getStatus()).isEqualTo(201);
        JsonNode attempt = json.readTree(response.getResponse().getContentAsString());
        List<UUID> snapshots = new ArrayList<>();
        attempt.get("questions")
                .forEach(q -> snapshots.add(UUID.fromString(q.get("snapshotId").asText())));
        return new Started(UUID.fromString(attempt.get("id").asText()), snapshots);
    }

    /** Status and error code of one start call; each call reads only its own response. */
    private Outcome startOutcome(UserPrincipal learner, UUID topic, int count, UUID key)
            throws Exception {
        var response = mockMvc.perform(startRequest(learner, topic, count, key)).andReturn();
        int status = response.getResponse().getStatus();
        String code =
                status == 201 || status == 200
                        ? ""
                        : json.readTree(response.getResponse().getContentAsString())
                                .path("code")
                                .asText("");
        return new Outcome(status, code);
    }

    private MockHttpServletRequestBuilder startRequest(
            UserPrincipal learner, UUID topic, int count, UUID key) {
        return post("/api/v1/attempts")
                .with(user(learner))
                .with(csrf())
                .header("Idempotency-Key", key.toString())
                .contentType("application/json")
                .content(
                        "{\"source\":{\"type\":\"GRAMMAR_TOPIC\",\"topicId\":\""
                                + topic
                                + "\"},\"questionCount\":"
                                + count
                                + "}");
    }

    private int answer(
            UserPrincipal learner, UUID attempt, UUID snapshot, UUID option, long version)
            throws Exception {
        return mockMvc.perform(
                        put("/api/v1/attempts/" + attempt + "/answers/" + snapshot)
                                .with(user(learner))
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        "{\"selectedOptionId\":\""
                                                + option
                                                + "\",\"expectedVersion\":"
                                                + version
                                                + "}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int submit(UserPrincipal learner, UUID attempt) throws Exception {
        return mockMvc.perform(
                        post("/api/v1/attempts/" + attempt + "/submit")
                                .with(user(learner))
                                .with(csrf()))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private long answerVersion(UUID snapshot) {
        return jdbc.queryForObject(
                "SELECT version FROM attempt_answers WHERE snapshot_id = ?", Long.class, snapshot);
    }

    private UUID selectedOption(UUID snapshot) {
        return jdbc.queryForObject(
                "SELECT selected_option_id FROM attempt_answers WHERE snapshot_id = ?",
                UUID.class,
                snapshot);
    }

    private String attemptStatus(UUID attempt) {
        return jdbc.queryForObject(
                "SELECT status FROM attempts WHERE id = ?", String.class, attempt);
    }

    private long attemptCorrectCount(UUID attempt) {
        return jdbc.queryForObject(
                "SELECT correct_count FROM attempts WHERE id = ?", Long.class, attempt);
    }

    private long countAttempts(UserPrincipal learner) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM attempts WHERE user_id = ?", Long.class, learner.getId());
    }

    private long countOpenAttempts(UserPrincipal learner) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM attempts WHERE user_id = ? AND status = 'IN_PROGRESS'",
                Long.class,
                learner.getId());
    }

    private User account(String prefix, Role role) {
        User account =
                new User(
                        prefix + "-" + suffix() + "@example.com",
                        passwordEncoder.encode(PASSWORD),
                        "Concurrent",
                        prefix);
        account.addRole(role);
        account.markEmailVerified(Instant.now());
        return userRepository.saveAndFlush(account);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
