package com.masterenglish.api.modules.practice.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.infrastructure.security.UserPrincipal;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.service.ContentAuthoringService;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.context.WebApplicationContext;

/**
 * GET /api/v1/grammar/topics/{id}/exercises over content published through the real pipeline.
 * Not @Transactional: publication commits on its own, as in QuestionPublicationIntegrationTests.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class GrammarPracticeControllerIntegrationTests {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ContentAuthoringService authoring;
    @Autowired private ContentRevisionRepository revisions;
    @Autowired private ObjectMapper mapper;

    private MockMvc mockMvc;
    private User learner;
    private UUID author;
    private UUID source;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Role learnerRole = entityManager.find(Role.class, (short) 1);
        learner =
                new User(
                        "learner-practice-" + suffix() + "@example.com",
                        passwordEncoder.encode("Password123!@#"),
                        "Learner",
                        "Practice");
        learner.addRole(learnerRole);
        learner.markEmailVerified(Instant.now());
        learner = userRepository.saveAndFlush(learner);

        author = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, first_name, last_name, status, email_verified_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW())",
                author,
                "exercise-author-" + suffix() + "@example.com",
                "$2a$12$e8YkC6XQ8XbY0u9VqT6eIeaFmP/2eZ.z1c8yU2MvX3mN.rLw5QkOe",
                "Exercise",
                "Author");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (?,2)", author);
        source =
                authoring
                        .createSource(
                                UUID.randomUUID(),
                                "Exercise source",
                                "SELF_AUTHORED",
                                null,
                                "Exercise author",
                                "SELF_AUTHORED",
                                null,
                                "Exercise attribution",
                                "Permission granted",
                                author)
                        .getId();
    }

    @Test
    void exercisesCountOnlyPublishedAndVisibleQuestions() throws Exception {
        UUID topic = publishTopic();
        publishQuestion(topic);
        publishQuestion(topic);
        UUID archived = publishQuestion(topic);
        authoring.archiveItem(archived, "Retired in test", author);
        draft(UUID.randomUUID(), questionJson(topic), source, "QUESTION");

        mockMvc.perform(
                        get("/api/v1/grammar/topics/" + topic + "/exercises")
                                .with(user(UserPrincipal.create(learner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuestions").value(2));
    }

    private UUID publishTopic() {
        UUID item = UUID.randomUUID();
        publish(approve(draft(item, topicJson(), source, "GRAMMAR_TOPIC")));
        return item;
    }

    private UUID publishQuestion(UUID topic) {
        UUID item = UUID.randomUUID();
        publish(approve(draft(item, questionJson(topic), source, "QUESTION")));
        return item;
    }

    private UUID draft(UUID item, String snapshot, UUID sourceId, String type) {
        authoring.createItemWithDraft(
                item, type, author, null, null, snapshot, "exercise draft", sourceId);
        return revisions.findByContentItemIdOrderByVersionNoDesc(item).get(0).getId();
    }

    private UUID approve(UUID draftRevision) {
        var inReview = authoring.submitForReview(draftRevision, author);
        return authoring.approve(inReview.getId(), author, "Content verified").getId();
    }

    private UUID publish(UUID approvedRevision) {
        return authoring.publish(approvedRevision, author).getId();
    }

    private String questionJson(UUID topic) {
        Map<String, Object> data = new LinkedHashMap<>();
        UUID correct = UUID.randomUUID();
        UUID wrong = UUID.randomUUID();
        data.put("category", "GRAMMAR");
        data.put("part_number", null);
        data.put("question_type_code", "SINGLE_CHOICE");
        data.put("practice_mode", "STANDARD");
        data.put("difficulty", "HARD");
        data.put("prompt", "Exercise " + suffix());
        data.put("group", null);
        data.put(
                "options",
                List.of(
                        Map.of("id", correct.toString(), "position", 1, "body", "goes"),
                        Map.of("id", wrong.toString(), "position", 2, "body", "go")));
        data.put("answer_key", Map.of("correctOptionId", correct.toString()));
        data.put("explanation", "Third person singular takes -s.");
        data.put("materials", List.of());
        data.put("topic_ids", List.of(topic.toString()));
        data.put("word_sense_ids", List.of());
        try {
            return mapper.writeValueAsString(data);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String topicJson() {
        return "{\"slug\": \"exercise-topic-"
                + suffix()
                + "\", \"name\": \"Exercise topic\", \"description\": \"A and an\","
                + " \"difficulty\": \"BASIC\", \"sort_order\": 1}";
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
