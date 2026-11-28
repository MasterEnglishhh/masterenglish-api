package com.masterenglish.api.modules.learning.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.infrastructure.security.UserPrincipal;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class LessonProgressControllerIntegrationTests {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private User learner;
    private User otherLearner;
    private User adminOnly;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Role learnerRole = entityManager.find(Role.class, (short) 1);
        Role adminRole = entityManager.find(Role.class, (short) 2);
        learner = learnerAccount("learner-progress@example.com", "Progress", learnerRole);
        otherLearner = learnerAccount("other-progress@example.com", "Other", learnerRole);
        adminOnly =
                new User(
                        "admin-progress@example.com",
                        passwordEncoder.encode("Password123!@#"),
                        "Admin",
                        "Progress");
        adminOnly.addRole(adminRole);
        adminOnly.markEmailVerified(Instant.now());
        adminOnly = userRepository.saveAndFlush(adminOnly);
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(
                        put(progressUrl(UUID.randomUUID()))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"state\":\"COMPLETED\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminWithoutLearnerRoleIsForbidden() throws Exception {
        UUID lesson = publishedLesson();
        update(lesson, "COMPLETED", adminOnly).andExpect(status().isForbidden());
    }

    @Test
    void invalidStateIsRejectedAsValidationError() throws Exception {
        UUID lesson = publishedLesson();
        update(lesson, "MASTERED", learner).andExpect(status().isBadRequest());
        assertRowCount(learner, lesson, 0);
    }

    @Test
    void completingVisibleLessonPinsTheCurrentPublishedRevision() throws Exception {
        UUID lesson = publishedLesson();
        UUID revision = currentRevision(lesson);

        update(lesson, "COMPLETED", learner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lessonId").value(lesson.toString()))
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.lastCompletedRevisionId").value(revision.toString()))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());
        assertRowCount(learner, lesson, 1);
    }

    @Test
    void completingAStartedLessonUpgradesTheSameRow() throws Exception {
        UUID lesson = publishedLesson();
        UUID revision = currentRevision(lesson);

        update(lesson, "IN_PROGRESS", learner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.completedAt").isEmpty());
        update(lesson, "COMPLETED", learner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.lastCompletedRevisionId").value(revision.toString()));
        assertRowCount(learner, lesson, 1);
    }

    @Test
    void startingALessonNeverDowngradesACompletedRecord() throws Exception {
        UUID lesson = publishedLesson();
        UUID revision = currentRevision(lesson);
        update(lesson, "COMPLETED", learner).andExpect(status().isOk());

        update(lesson, "IN_PROGRESS", learner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.lastCompletedRevisionId").value(revision.toString()));
    }

    @Test
    void repeatedCompletionIsIdempotentAndKeepsTheFirstRecord() throws Exception {
        UUID lesson = publishedLesson();
        UUID firstRevision = currentRevision(lesson);
        String firstCompletedAt = completedAt(update(lesson, "COMPLETED", learner));

        republishLesson(lesson);
        update(lesson, "COMPLETED", learner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastCompletedRevisionId").value(firstRevision.toString()))
                .andExpect(jsonPath("$.completedAt").value(firstCompletedAt));
        assertRowCount(learner, lesson, 1);
    }

    @Test
    void newPublicationDoesNotChangeTheStoredCompletion() throws Exception {
        UUID lesson = publishedLesson();
        UUID firstRevision = currentRevision(lesson);
        update(lesson, "COMPLETED", learner).andExpect(status().isOk());

        republishLesson(lesson);

        mockMvc.perform(get("/api/v1/grammar/progress").with(user(UserPrincipal.create(learner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].lessonId").value(lesson.toString()))
                .andExpect(
                        jsonPath("$[0].lastCompletedRevisionId").value(firstRevision.toString()));
    }

    @Test
    void unknownOrArchivedLessonIsNotFoundAndWritesNothing() throws Exception {
        UUID archived = publishedLesson();
        archive(archived);

        update(UUID.randomUUID(), "COMPLETED", learner).andExpect(status().isNotFound());
        update(archived, "COMPLETED", learner).andExpect(status().isNotFound());
        assertRowCount(learner, archived, 0);
    }

    @Test
    void progressListsOnlyTheCurrentLearnersRecords() throws Exception {
        UUID mine = publishedLesson();
        UUID theirs = publishedLesson();
        update(mine, "COMPLETED", learner).andExpect(status().isOk());
        update(theirs, "COMPLETED", otherLearner).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/grammar/progress").with(user(UserPrincipal.create(learner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].lessonId").value(mine.toString()));
    }

    @Test
    void progressFlagsANewerRevisionAndKeepsTheCompletion() throws Exception {
        UUID lesson = publishedLesson();
        update(lesson, "COMPLETED", learner).andExpect(jsonPath("$.hasUpdate").value(false));

        republishLesson(lesson);

        mockMvc.perform(get("/api/v1/grammar/progress").with(user(UserPrincipal.create(learner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("COMPLETED"))
                .andExpect(jsonPath("$[0].hasUpdate").value(true));
    }

    @Test
    void topicProgressCountsVisibleLessonsAndFlagsNewerRevisions() throws Exception {
        UUID topic = publishTopic();
        UUID first = publishedLessonIn(topic);
        UUID second = publishedLessonIn(topic);
        update(first, "COMPLETED", learner).andExpect(status().isOk());

        topicProgress()
                .andExpect(status().isOk())
                .andExpect(jsonPath(topicPath(topic, "completedLessonCount")).value(1))
                .andExpect(jsonPath(topicPath(topic, "totalLessonCount")).value(2))
                .andExpect(jsonPath(topicPath(topic, "hasUpdate")).value(false));

        republishLesson(first);
        topicProgress()
                .andExpect(jsonPath(topicPath(topic, "completedLessonCount")).value(1))
                .andExpect(jsonPath(topicPath(topic, "hasUpdate")).value(true));

        archive(second);
        topicProgress()
                .andExpect(jsonPath(topicPath(topic, "completedLessonCount")).value(1))
                .andExpect(jsonPath(topicPath(topic, "totalLessonCount")).value(1));
    }

    private ResultActions topicProgress() throws Exception {
        return mockMvc.perform(
                get("/api/v1/grammar/progress/topics").with(user(UserPrincipal.create(learner))));
    }

    private static String topicPath(UUID topic, String field) {
        return "$[?(@.topicId == '" + topic + "')]." + field;
    }

    private ResultActions update(UUID lesson, String state, User account) throws Exception {
        return mockMvc.perform(
                put(progressUrl(lesson))
                        .with(user(UserPrincipal.create(account)))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"state\":\"" + state + "\"}"));
    }

    private static String completedAt(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.completedAt");
    }

    private User learnerAccount(String email, String lastName, Role learnerRole) {
        User account =
                new User(email, passwordEncoder.encode("Password123!@#"), "Learner", lastName);
        account.addRole(learnerRole);
        account.markEmailVerified(Instant.now());
        return userRepository.saveAndFlush(account);
    }

    private static String progressUrl(UUID lessonId) {
        return "/api/v1/grammar/lessons/" + lessonId + "/progress";
    }

    private void assertRowCount(User account, UUID lesson, int expected) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM lesson_progress WHERE user_id = ? AND lesson_id = ?",
                        Integer.class,
                        account.getId(),
                        lesson);
        assertThat(count).isEqualTo(expected);
    }

    /**
     * Publishes a lesson the same way content publication does: item, then revision, then
     * projection.
     */
    private UUID publishedLesson() {
        return publishedLessonIn(publishTopic());
    }

    private UUID publishedLessonIn(UUID topic) {
        UUID item = publishItem("GRAMMAR_LESSON");
        UUID revision = currentRevision(item);
        jdbc.update(
                "INSERT INTO grammar_lessons (id, published_revision_id, topic_id, title, body, sort_order)"
                        + " VALUES (?, ?, ?, ?, ?::jsonb, ?)",
                item,
                revision,
                topic,
                "Lesson " + suffix(),
                "{\"blocks\":[]}",
                1);
        return item;
    }

    private UUID publishTopic() {
        UUID item = publishItem("GRAMMAR_TOPIC");
        UUID revision = currentRevision(item);
        jdbc.update(
                "INSERT INTO grammar_topics (id, published_revision_id, slug, name, difficulty, sort_order)"
                        + " VALUES (?, ?, ?, ?, 'BASIC', ?)",
                item,
                revision,
                "topic-" + suffix(),
                "Topic " + suffix(),
                1);
        return item;
    }

    /** Publishes a newer revision of an existing lesson and moves the projection to it. */
    private void republishLesson(UUID lesson) {
        UUID revision = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO content_revisions (id, content_item_id, version_no, snapshot, status, created_by)"
                        + " VALUES (?, ?, 2, '{}'::jsonb, 'DRAFT', ?)",
                revision,
                lesson,
                adminOnly.getId());
        jdbc.update(
                "UPDATE content_revisions SET status = 'PUBLISHED', reviewed_by = ?, reviewed_at = now(),"
                        + " published_at = now() WHERE id = ?",
                adminOnly.getId(),
                revision);
        jdbc.update(
                "UPDATE content_items SET published_revision_id = ? WHERE id = ?",
                revision,
                lesson);
        jdbc.update(
                "UPDATE grammar_lessons SET published_revision_id = ? WHERE id = ?",
                revision,
                lesson);
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
