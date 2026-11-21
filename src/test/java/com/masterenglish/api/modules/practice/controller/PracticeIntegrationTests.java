package com.masterenglish.api.modules.practice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.events.ContentEvents.RevisionPublished;
import com.masterenglish.api.infrastructure.security.UserPrincipal;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class PracticeIntegrationTests {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private ObjectMapper mapper;

    private MockMvc mockMvc;
    private User learner;
    private User otherLearner;
    private User adminOnly;

    /** Correct option body per prompt, so tests can answer without reading hidden flags. */
    private final Map<String, String> correctByPrompt = new HashMap<>();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Role learnerRole = entityManager.find(Role.class, (short) 1);
        Role adminRole = entityManager.find(Role.class, (short) 2);
        learner = learnerAccount("learner-practice@example.com", learnerRole);
        otherLearner = learnerAccount("other-practice@example.com", learnerRole);
        adminOnly =
                new User(
                        "admin-practice@example.com",
                        passwordEncoder.encode("Password123!@#"),
                        "Admin",
                        "Practice");
        adminOnly.addRole(adminRole);
        adminOnly.markEmailVerified(Instant.now());
        adminOnly = userRepository.saveAndFlush(adminOnly);
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/attempts/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminWithoutLearnerRoleCannotStartAttempts() throws Exception {
        UUID topic = publishTopic();
        publishQuestion(topic, "Admin prompt", "right", "wrong");
        startTopicRaw(adminOnly, UUID.randomUUID(), null, topic).andExpect(status().isForbidden());
    }

    @Test
    void exercisesCountOnlyPublishedAndVisibleQuestions() throws Exception {
        UUID topic = publishTopic();
        publishQuestion(topic, "Count one", "a", "b");
        publishQuestion(topic, "Count two", "a", "b");
        UUID archived = publishQuestion(topic, "Count archived", "a", "b");
        archive(archived);

        mockMvc.perform(
                        get("/api/v1/grammar/topics/" + topic + "/exercises")
                                .with(user(UserPrincipal.create(learner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuestions").value(2));
    }

    @Test
    void startingAttemptSealsQuestionsWithoutRevealingAnswers() throws Exception {
        UUID topic = publishTopic();
        publishQuestion(topic, "Seal one", "right-1", "wrong-1");
        publishQuestion(topic, "Seal two", "right-2", "wrong-2");

        JsonNode attempt = startTopic(learner, UUID.randomUUID(), null, topic, 201);

        assertThat(attempt.path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(attempt.path("totalCount").asInt()).isEqualTo(2);
        assertThat(attempt.path("questions")).hasSize(2);
        for (JsonNode question : attempt.path("questions")) {
            assertAbsent(question.path("explanation"));
            assertAbsent(question.path("correct"));
            for (JsonNode option : question.path("options")) {
                assertAbsent(option.path("correct"));
            }
        }
    }

    @Test
    void sameRequestIdReplaysTheAttemptAndRejectsADifferentRequest() throws Exception {
        UUID topic = publishTopic();
        publishQuestion(topic, "Replay one", "right", "wrong");
        publishQuestion(topic, "Replay two", "right", "wrong");
        UUID requestId = UUID.randomUUID();

        JsonNode first = startTopic(learner, requestId, 1, topic, 201);
        JsonNode replay = startTopic(learner, requestId, 1, topic, 200);
        assertThat(replay.path("id").asText()).isEqualTo(first.path("id").asText());

        startTopicRaw(learner, requestId, 2, topic).andExpect(status().isConflict());
    }

    @Test
    void answersAcceptOwnSnapshotOptionsOnly() throws Exception {
        UUID topic = publishTopic();
        publishQuestion(topic, "Answer one", "right-1", "wrong-1");
        publishQuestion(topic, "Answer two", "right-2", "wrong-2");
        JsonNode attempt = startTopic(learner, UUID.randomUUID(), null, topic, 201);
        UUID attemptId = UUID.fromString(attempt.path("id").asText());
        String optionOfSecond = optionIdWithBody(attempt, 2, correctByPrompt(attempt, 2));

        answer(learner, attemptId, 1, optionOfSecond, 400);
        answer(learner, attemptId, 9, optionOfSecond, 404);
        answer(
                learner,
                attemptId,
                1,
                optionIdWithBody(attempt, 1, correctByPrompt(attempt, 1)),
                200);
    }

    @Test
    void submitGradesOnServerRevealsAnswersAndLocksFurtherChanges() throws Exception {
        UUID topic = publishTopic();
        publishQuestion(topic, "Grade one", "right-1", "wrong-1");
        publishQuestion(topic, "Grade two", "right-2", "wrong-2");
        JsonNode attempt = startTopic(learner, UUID.randomUUID(), null, topic, 201);
        UUID attemptId = UUID.fromString(attempt.path("id").asText());
        answer(
                learner,
                attemptId,
                1,
                optionIdWithBody(attempt, 1, correctByPrompt(attempt, 1)),
                200);
        answer(learner, attemptId, 2, optionIdWithBody(attempt, 2, wrongOf(attempt, 2)), 200);

        JsonNode submitted = submit(learner, attemptId);

        assertThat(submitted.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(submitted.path("correctCount").asInt()).isEqualTo(1);
        assertThat(submitted.path("score").asInt()).isEqualTo(50);
        assertThat(submitted.path("questions").get(0).path("correct").asBoolean()).isTrue();
        assertThat(submitted.path("questions").get(1).path("correct").asBoolean()).isFalse();
        assertThat(submitted.path("questions").get(0).path("explanation").asText()).isNotBlank();

        JsonNode again = submit(learner, attemptId);
        assertThat(again.path("score").asInt()).isEqualTo(50);
        assertThat(again.path("correctCount").asInt()).isEqualTo(1);

        answer(learner, attemptId, 1, optionIdWithBody(attempt, 1, wrongOf(attempt, 1)), 409);
    }

    @Test
    void unansweredQuestionsCountAsWrongInStatistics() throws Exception {
        UUID topic = publishTopic();
        publishQuestion(topic, "Stats one", "right-1", "wrong-1");
        publishQuestion(topic, "Stats two", "right-2", "wrong-2");
        JsonNode attempt = startTopic(learner, UUID.randomUUID(), null, topic, 201);
        UUID attemptId = UUID.fromString(attempt.path("id").asText());
        answer(
                learner,
                attemptId,
                1,
                optionIdWithBody(attempt, 1, correctByPrompt(attempt, 1)),
                200);
        submit(learner, attemptId);

        mockMvc.perform(
                        get("/api/v1/grammar/statistics/topics")
                                .with(user(UserPrincipal.create(learner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].topicId").value(topic.toString()))
                .andExpect(jsonPath("$[0].attemptCount").value(1))
                .andExpect(jsonPath("$[0].questionCount").value(2))
                .andExpect(jsonPath("$[0].correctCount").value(1))
                .andExpect(jsonPath("$[0].accuracyPercent").value(50));
    }

    @Test
    void statisticsAreEmptyBeforeAnySubmission() throws Exception {
        UUID topic = publishTopic();
        publishQuestion(topic, "Empty stats", "right", "wrong");
        startTopic(learner, UUID.randomUUID(), null, topic, 201);

        mockMvc.perform(
                        get("/api/v1/grammar/statistics/topics")
                                .with(user(UserPrincipal.create(learner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void otherLearnerCannotReadAnswerOrSubmitAnAttempt() throws Exception {
        UUID topic = publishTopic();
        publishQuestion(topic, "Isolation one", "right", "wrong");
        JsonNode attempt = startTopic(learner, UUID.randomUUID(), 1, topic, 201);
        UUID attemptId = UUID.fromString(attempt.path("id").asText());

        mockMvc.perform(
                        get("/api/v1/attempts/" + attemptId)
                                .with(user(UserPrincipal.create(otherLearner))))
                .andExpect(status().isNotFound());
        answer(
                otherLearner,
                attemptId,
                1,
                optionIdWithBody(attempt, 1, correctByPrompt(attempt, 1)),
                404);
        mockMvc.perform(
                        post("/api/v1/attempts/" + attemptId + "/submit")
                                .with(user(UserPrincipal.create(otherLearner)))
                                .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void mixedAttemptDrawsOnlyFromSelectedTopics() throws Exception {
        UUID topicA = publishTopic();
        UUID topicB = publishTopic();
        UUID topicC = publishTopic();
        publishQuestion(topicA, "Mixed from A", "a", "x");
        publishQuestion(topicB, "Mixed from B", "b", "y");
        publishQuestion(topicC, "Mixed from C", "c", "z");

        JsonNode attempt =
                startMixed(learner, UUID.randomUUID(), List.of(topicA, topicB), null, 201);

        assertThat(attempt.path("sourceType").asText()).isEqualTo("MIXED_GRAMMAR");
        assertThat(attempt.path("totalCount").asInt()).isEqualTo(2);
        List<String> prompts = new java.util.ArrayList<>();
        attempt.path("questions").forEach(q -> prompts.add(q.path("prompt").asText()));
        assertThat(prompts).containsExactlyInAnyOrder("Mixed from A", "Mixed from B");
    }

    @Test
    void mixedAttemptRejectsDuplicateAndHiddenTopics() throws Exception {
        UUID topic = publishTopic();
        publishQuestion(topic, "Mixed guard", "a", "x");
        mockMvc.perform(
                        post("/api/v1/grammar/attempts/mixed")
                                .with(user(UserPrincipal.create(learner)))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mixedBody(UUID.randomUUID(), List.of(topic, topic), null)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        post("/api/v1/grammar/attempts/mixed")
                                .with(user(UserPrincipal.create(learner)))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mixedBody(
                                                UUID.randomUUID(),
                                                List.of(topic, UUID.randomUUID()),
                                                null)))
                .andExpect(status().isNotFound());
    }

    @Test
    void republishingDoesNotChangeASealedAttempt() throws Exception {
        UUID topic = publishTopic();
        UUID item = publishQuestion(topic, "Old prompt", "right", "wrong");
        JsonNode attempt = startTopic(learner, UUID.randomUUID(), 1, topic, 201);

        republishQuestion(item, topic, "New prompt", "right", "wrong");

        UUID attemptId = UUID.fromString(attempt.path("id").asText());
        mockMvc.perform(
                        get("/api/v1/attempts/" + attemptId)
                                .with(user(UserPrincipal.create(learner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].prompt").value("Old prompt"));
    }

    // ---- request helpers ------------------------------------------------------------------

    private JsonNode startTopic(
            User account, UUID requestId, Integer count, UUID topic, int expected)
            throws Exception {
        return read(
                startTopicRaw(account, requestId, count, topic).andExpect(status().is(expected)));
    }

    private ResultActions startTopicRaw(User account, UUID requestId, Integer count, UUID topic)
            throws Exception {
        return mockMvc.perform(
                post("/api/v1/grammar/topics/" + topic + "/attempts")
                        .with(user(UserPrincipal.create(account)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody(requestId, count)));
    }

    private JsonNode startMixed(
            User account, UUID requestId, List<UUID> topics, Integer count, int expected)
            throws Exception {
        return read(
                mockMvc.perform(
                                post("/api/v1/grammar/attempts/mixed")
                                        .with(user(UserPrincipal.create(account)))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(mixedBody(requestId, topics, count)))
                        .andExpect(status().is(expected)));
    }

    private void answer(User account, UUID attemptId, int position, String optionId, int expected)
            throws Exception {
        mockMvc.perform(
                        put("/api/v1/attempts/" + attemptId + "/answers/" + position)
                                .with(user(UserPrincipal.create(account)))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"optionId\":\"" + optionId + "\"}"))
                .andExpect(status().is(expected));
    }

    private JsonNode submit(User account, UUID attemptId) throws Exception {
        return read(
                mockMvc.perform(
                                post("/api/v1/attempts/" + attemptId + "/submit")
                                        .with(user(UserPrincipal.create(account)))
                                        .with(csrf()))
                        .andExpect(status().isOk()));
    }

    private JsonNode read(ResultActions result) throws Exception {
        MvcResult mvc = result.andReturn();
        return mapper.readTree(mvc.getResponse().getContentAsString());
    }

    private String startBody(UUID requestId, Integer count) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId.toString());
        if (count != null) {
            body.put("questionCount", count);
        }
        return mapper.writeValueAsString(body);
    }

    private String mixedBody(UUID requestId, List<UUID> topics, Integer count) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId.toString());
        body.put("topicIds", topics.stream().map(UUID::toString).toList());
        if (count != null) {
            body.put("questionCount", count);
        }
        return mapper.writeValueAsString(body);
    }

    private static String optionIdWithBody(JsonNode attempt, int position, String body) {
        for (JsonNode option : attempt.path("questions").get(position - 1).path("options")) {
            if (option.path("body").asText().equals(body)) {
                return option.path("id").asText();
            }
        }
        throw new AssertionError("No option with body " + body + " at position " + position);
    }

    private String correctByPrompt(JsonNode attempt, int position) {
        return correctByPrompt.get(
                attempt.path("questions").get(position - 1).path("prompt").asText());
    }

    /** The distractor is whichever option body is not the correct one for this question. */
    private String wrongOf(JsonNode attempt, int position) {
        JsonNode question = attempt.path("questions").get(position - 1);
        String correct = correctByPrompt(attempt, position);
        for (JsonNode option : question.path("options")) {
            if (!option.path("body").asText().equals(correct)) {
                return option.path("body").asText();
            }
        }
        throw new AssertionError("No distractor at position " + position);
    }

    private static void assertAbsent(JsonNode node) {
        assertThat(node.isNull() || node.isMissingNode())
                .as("field must be hidden: %s", node)
                .isTrue();
    }

    // ---- fixtures -------------------------------------------------------------------------

    private User learnerAccount(String email, Role learnerRole) {
        User account =
                new User(email, passwordEncoder.encode("Password123!@#"), "Learner", "Practice");
        account.addRole(learnerRole);
        account.markEmailVerified(Instant.now());
        return userRepository.saveAndFlush(account);
    }

    private UUID publishTopic() {
        UUID item = publishItem("GRAMMAR_TOPIC");
        jdbc.update(
                "INSERT INTO grammar_topics (id, published_revision_id, slug, name, difficulty, sort_order)"
                        + " VALUES (?, ?, ?, ?, 'BASIC', ?)",
                item,
                currentRevision(item),
                "topic-" + suffix(),
                "Topic " + suffix(),
                1);
        return item;
    }

    /** Publishes a question the way content does: item, revision, then the projection event. */
    private UUID publishQuestion(UUID topic, String prompt, String correct, String wrong) {
        UUID item = publishItem("QUESTION");
        correctByPrompt.put(prompt, correct);
        events.publishEvent(
                new RevisionPublished(
                        item,
                        currentRevision(item),
                        "QUESTION",
                        snapshot(topic, prompt, correct, wrong)));
        return item;
    }

    private void republishQuestion(
            UUID item, UUID topic, String prompt, String correct, String wrong) {
        UUID revision = UUID.randomUUID();
        correctByPrompt.put(prompt, correct);
        jdbc.update(
                "INSERT INTO content_revisions (id, content_item_id, version_no, snapshot, status, created_by)"
                        + " VALUES (?, ?, 2, '{}'::jsonb, 'DRAFT', ?)",
                revision,
                item,
                adminOnly.getId());
        jdbc.update(
                "UPDATE content_revisions SET status = 'PUBLISHED', reviewed_by = ?, reviewed_at = now(),"
                        + " published_at = now() WHERE id = ?",
                adminOnly.getId(),
                revision);
        jdbc.update(
                "UPDATE content_items SET published_revision_id = ? WHERE id = ?", revision, item);
        events.publishEvent(
                new RevisionPublished(
                        item, revision, "QUESTION", snapshot(topic, prompt, correct, wrong)));
    }

    private String snapshot(UUID topic, String prompt, String correct, String wrong) {
        try {
            return mapper.writeValueAsString(
                    Map.of(
                            "category",
                            "GRAMMAR",
                            "prompt",
                            prompt,
                            "explanation",
                            "Why: " + prompt,
                            "topic_ids",
                            List.of(topic.toString()),
                            "options",
                            List.of(
                                    Map.of("body", correct, "is_correct", true),
                                    Map.of("body", wrong, "is_correct", false))));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
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

    private void archive(UUID item) {
        jdbc.update(
                "UPDATE content_items SET is_archived = true, archived_at = now() WHERE id = ?",
                item);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
