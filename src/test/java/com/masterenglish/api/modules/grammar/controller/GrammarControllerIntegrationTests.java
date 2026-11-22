package com.masterenglish.api.modules.grammar.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class GrammarControllerIntegrationTests {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private User learner;
    private User adminOnly;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Role learnerRole = entityManager.find(Role.class, (short) 1);
        Role adminRole = entityManager.find(Role.class, (short) 2);

        learner =
                new User(
                        "learner-grammar@example.com",
                        passwordEncoder.encode("Password123!@#"),
                        "Learner",
                        "Grammar");
        learner.addRole(learnerRole);
        learner.markEmailVerified(Instant.now());
        learner = userRepository.saveAndFlush(learner);

        adminOnly =
                new User(
                        "admin-grammar@example.com",
                        passwordEncoder.encode("Password123!@#"),
                        "Admin",
                        "Grammar");
        adminOnly.addRole(adminRole);
        adminOnly.markEmailVerified(Instant.now());
        adminOnly = userRepository.saveAndFlush(adminOnly);
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/grammar/topics")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminWithoutLearnerRoleIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/grammar/topics").with(user(UserPrincipal.create(adminOnly))))
                .andExpect(status().isForbidden());
    }

    @Test
    void learnerSeesOnlyVisibleTopicsInOrder() throws Exception {
        UUID later = publishTopic("present-" + suffix(), "Present tenses", 2);
        UUID earlier = publishTopic("articles-" + suffix(), "Articles", 1);
        UUID archived = publishTopic("hidden-" + suffix(), "Hidden", 0);
        archive(archived);

        mockMvc.perform(get("/api/v1/grammar/topics").with(user(UserPrincipal.create(learner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(earlier.toString()))
                .andExpect(jsonPath("$.items[1].id").value(later.toString()));
    }

    @Test
    void learnerSeesOnlyVisibleLessonsOfATopic() throws Exception {
        UUID topic = publishTopic("tenses-" + suffix(), "Tenses", 1);
        UUID second = publishLesson(topic, "Simple past", 2, "{\"blocks\":[]}");
        UUID first = publishLesson(topic, "Simple present", 1, "{\"blocks\":[]}");
        UUID hidden = publishLesson(topic, "Hidden lesson", 3, "{\"blocks\":[]}");
        archive(hidden);

        mockMvc.perform(
                        get("/api/v1/grammar/topics/" + topic + "/lessons")
                                .with(user(UserPrincipal.create(learner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first.toString()))
                .andExpect(jsonPath("$[1].id").value(second.toString()));
    }

    @Test
    void lessonsOfAHiddenTopicAreNotFound() throws Exception {
        UUID topic = publishTopic("gone-" + suffix(), "Gone", 1);
        publishLesson(topic, "Orphan", 1, "{\"blocks\":[]}");
        archive(topic);

        mockMvc.perform(
                        get("/api/v1/grammar/topics/" + topic + "/lessons")
                                .with(user(UserPrincipal.create(learner))))
                .andExpect(status().isNotFound());
    }

    @Test
    void lessonDetailReturnsStoredBody() throws Exception {
        UUID topic = publishTopic("body-" + suffix(), "Body", 1);
        UUID lesson =
                publishLesson(
                        topic,
                        "Articles",
                        1,
                        "{\"blocks\":[{\"type\":\"paragraph\",\"text\":\"Use a before consonants.\"}]}");

        mockMvc.perform(
                        get("/api/v1/grammar/lessons/" + lesson)
                                .with(user(UserPrincipal.create(learner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(lesson.toString()))
                .andExpect(jsonPath("$.topicId").value(topic.toString()))
                .andExpect(jsonPath("$.body.blocks[0].type").value("paragraph"));
    }

    @Test
    void unknownLessonIsNotFound() throws Exception {
        mockMvc.perform(
                        get("/api/v1/grammar/lessons/" + UUID.randomUUID())
                                .with(user(UserPrincipal.create(learner))))
                .andExpect(status().isNotFound());
    }

    @Test
    void topicListReturnsAPageWithDefaults() throws Exception {
        UUID first = publishTopic("pd-a-" + suffix(), "Pd A", 1);
        publishTopic("pd-b-" + suffix(), "Pd B", 2);
        publishTopic("pd-c-" + suffix(), "Pd C", 3);

        mockMvc.perform(get("/api/v1/grammar/topics").with(user(UserPrincipal.create(learner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].id").value(first.toString()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void topicListPagesAndReportsHasNext() throws Exception {
        publishTopic("pn-a-" + suffix(), "Pn A", 1);
        publishTopic("pn-b-" + suffix(), "Pn B", 2);
        publishTopic("pn-c-" + suffix(), "Pn C", 3);

        mockMvc.perform(
                        get("/api/v1/grammar/topics?page=0&size=2")
                                .with(user(UserPrincipal.create(learner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));

        mockMvc.perform(
                        get("/api/v1/grammar/topics?page=1&size=2")
                                .with(user(UserPrincipal.create(learner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Pn C"))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void topicListSortsByAnAllowedFieldAndDirection() throws Exception {
        publishTopic("sd-a-" + suffix(), "Sd A", 1);
        publishTopic("sd-b-" + suffix(), "Sd B", 2);
        publishTopic("sd-c-" + suffix(), "Sd C", 3);

        mockMvc.perform(
                        get("/api/v1/grammar/topics?sortBy=name&sortDirection=desc")
                                .with(user(UserPrincipal.create(learner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Sd C"))
                .andExpect(jsonPath("$.items[2].name").value("Sd A"));
    }

    @Test
    void topicListRejectsInvalidPagingWithProblemDetail() throws Exception {
        for (String query :
                new String[] {
                    "page=-1", "size=0", "size=101", "sortBy=description", "sortDirection=up"
                }) {
            mockMvc.perform(
                            get("/api/v1/grammar/topics?" + query)
                                    .with(user(UserPrincipal.create(learner))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    /** Publishes a topic the same way content publication does: item, revision, then projection. */
    private UUID publishTopic(String slug, String name, int sortOrder) {
        UUID item = publishItem("GRAMMAR_TOPIC");
        UUID revision = currentRevision(item);
        jdbc.update(
                "INSERT INTO grammar_topics (id, published_revision_id, slug, name, difficulty, sort_order)"
                        + " VALUES (?, ?, ?, ?, 'BASIC', ?)",
                item,
                revision,
                slug,
                name,
                sortOrder);
        return item;
    }

    private UUID publishLesson(UUID topic, String title, int sortOrder, String body) {
        UUID item = publishItem("GRAMMAR_LESSON");
        UUID revision = currentRevision(item);
        jdbc.update(
                "INSERT INTO grammar_lessons (id, published_revision_id, topic_id, title, body, sort_order)"
                        + " VALUES (?, ?, ?, ?, ?::jsonb, ?)",
                item,
                revision,
                topic,
                title,
                body,
                sortOrder);
        return item;
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
