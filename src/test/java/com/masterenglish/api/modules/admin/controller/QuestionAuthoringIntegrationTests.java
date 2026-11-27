package com.masterenglish.api.modules.admin.controller;

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
import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.infrastructure.security.UserPrincipal;
import com.masterenglish.api.modules.admin.dto.request.ArchiveItemRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateGrammarDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateQuestionDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateRevisionFromPublishedRequest;
import com.masterenglish.api.modules.admin.dto.request.ReviewDecisionRequest;
import com.masterenglish.api.modules.admin.dto.request.UpdateRevisionDraftRequest;
import com.masterenglish.api.modules.content.entity.ContentItem;
import com.masterenglish.api.modules.content.entity.ContentRevision;
import com.masterenglish.api.modules.content.entity.ContentSource;
import com.masterenglish.api.modules.content.entity.RevisionDependency;
import com.masterenglish.api.modules.content.repository.AuditLogRepository;
import com.masterenglish.api.modules.content.repository.ContentItemRepository;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceLinkRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceRepository;
import com.masterenglish.api.modules.content.repository.RevisionDependencyRepository;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.RoleRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.practice.entity.Question;
import com.masterenglish.api.modules.practice.repository.QuestionOptionRepository;
import com.masterenglish.api.modules.practice.repository.QuestionRepository;
import com.masterenglish.api.modules.practice.repository.QuestionTopicRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@EnabledIf("isDockerAvailable")
class QuestionAuthoringIntegrationTests {

    static boolean isDockerAvailable() {
        try {
            org.testcontainers.DockerClientFactory.instance().client();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private ContentItemRepository itemRepository;
    @Autowired private ContentRevisionRepository revisionRepository;
    @Autowired private ContentSourceRepository sourceRepository;
    @Autowired private ContentSourceLinkRepository sourceLinkRepository;
    @Autowired private RevisionDependencyRepository dependencyRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private QuestionOptionRepository optionRepository;
    @Autowired private QuestionTopicRepository questionTopicRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private MockMvc mockMvc;
    private User contentAdminUser;
    private User learnerUser;
    private UserPrincipal contentAdminPrincipal;
    private UserPrincipal learnerPrincipal;
    private ContentSource activeSource;

    private UserPrincipal adminPrincipal() {
        return contentAdminPrincipal;
    }

    private UserPrincipal learnerPrincipal() {
        return learnerPrincipal;
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        transactionTemplate.execute(
                status -> {
                    Role adminRole =
                            roleRepository.findByCode(RoleCode.CONTENT_ADMIN).orElseThrow();
                    Role learnerRole = roleRepository.findByCode(RoleCode.LEARNER).orElseThrow();

                    contentAdminUser =
                            userRepository.save(
                                    new User(
                                            "admin-question-" + UUID.randomUUID() + "@example.com",
                                            "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                            "QuestionAdmin",
                                            "User"));
                    contentAdminUser.addRole(adminRole);
                    contentAdminUser.markEmailVerified(Instant.now());
                    contentAdminUser = userRepository.saveAndFlush(contentAdminUser);
                    contentAdminPrincipal = UserPrincipal.create(contentAdminUser);

                    learnerUser =
                            userRepository.save(
                                    new User(
                                            "learner-question-"
                                                    + UUID.randomUUID()
                                                    + "@example.com",
                                            "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                            "Learner",
                                            "User"));
                    learnerUser.addRole(learnerRole);
                    learnerUser.markEmailVerified(Instant.now());
                    learnerUser = userRepository.saveAndFlush(learnerUser);
                    learnerPrincipal = UserPrincipal.create(learnerUser);

                    activeSource =
                            sourceRepository.saveAndFlush(
                                    new ContentSource(
                                            UUID.randomUUID(),
                                            "Grammar Standard Source",
                                            "SELF_AUTHORED",
                                            "https://example.com/source",
                                            "MasterEnglish Team",
                                            "SELF_AUTHORED",
                                            "https://example.com/license",
                                            "MasterEnglish Team Attribution",
                                            "Internal authorization note",
                                            "ACTIVE",
                                            contentAdminUser.getId()));
                    return null;
                });
    }

    @Test
    @DisplayName("Tạo bản thảo QUESTION thành công với đầy đủ dữ liệu hợp lệ")
    void createQuestionDraftSuccess() throws Exception {
        UUID topicId = createAndPublishTopic("Nouns & Pronouns", "nouns-pronouns");
        UUID opt1 = UUID.randomUUID();
        UUID opt2 = UUID.randomUUID();
        UUID opt3 = UUID.randomUUID();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", "GRAMMAR");
        data.put("part_number", null);
        data.put("question_type_code", "SINGLE_CHOICE");
        data.put("practice_mode", "STANDARD");
        data.put("difficulty", "MEDIUM");
        data.put("prompt", "Choose the correct pronoun: ___ is going to the library.");
        data.put("group", null);
        data.put(
                "options",
                List.of(
                        Map.of("id", opt1.toString(), "position", 1, "body", "She"),
                        Map.of("id", opt2.toString(), "position", 2, "body", "Her"),
                        Map.of("id", opt3.toString(), "position", 3, "body", "Hers")));
        data.put("answer_key", Map.of("correctOptionId", opt1.toString()));
        data.put(
                "explanation", "Subject pronoun 'She' is required as the subject of the sentence.");
        data.put("materials", List.of());
        data.put("topic_ids", List.of(topicId.toString()));
        data.put("word_sense_ids", List.of());

        CreateQuestionDraftRequest request =
                new CreateQuestionDraftRequest(
                        data, List.of(activeSource.getId()), "Tạo câu hỏi đại từ nhân xưng");

        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/admin/content/questions")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.type").value("QUESTION"))
                        .andExpect(jsonPath("$.status").value("DRAFT"))
                        .andExpect(jsonPath("$.versionNo").value(1))
                        .andExpect(jsonPath("$.module").value("PRACTICE"))
                        .andExpect(jsonPath("$.level").value("MEDIUM"))
                        .andExpect(
                                jsonPath("$.title")
                                        .value(
                                                "Choose the correct pronoun: ___ is going to the"
                                                        + " library."))
                        .andReturn();

        JsonNode responseJson = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID itemId = UUID.fromString(responseJson.path("id").asText());

        ContentItem item = itemRepository.findById(itemId).orElseThrow();
        assertThat(item.getContentType()).isEqualTo("QUESTION");
        assertThat(item.getPublishedRevisionId()).isNull();

        List<ContentRevision> revs =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(itemId);
        assertThat(revs).hasSize(1);
        ContentRevision draftRev = revs.get(0);
        assertThat(draftRev.getStatus()).isEqualTo("DRAFT");
        assertThat(draftRev.getVersionNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("Chấp nhận bản nháp từng phần (partial draft) khi tạo câu hỏi")
    void createQuestionDraftPartialAccepted() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", "GRAMMAR");
        data.put("prompt", "Incomplete prompt for draft test.");

        CreateQuestionDraftRequest request =
                new CreateQuestionDraftRequest(
                        data, List.of(activeSource.getId()), "Khởi tạo partial draft");

        mockMvc.perform(
                        post("/api/v1/admin/content/questions")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("QUESTION"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.title").value("Incomplete prompt for draft test."));
    }

    @Test
    @DisplayName("Từ chối các trường không hợp lệ, sai kiểu dữ liệu hoặc vi phạm quy tắc soạn thảo")
    void createQuestionDraftValidationRejections() throws Exception {
        // 1. Nested data wrapper
        assertCreateDraftFails(
                Map.of("data", Map.of("prompt", "Nested")),
                status().isBadRequest(),
                "Unsupported snapshot field: data");

        // 2. Unknown field
        assertCreateDraftFails(
                Map.of("prompt", "Valid", "unknown_field", "slop"),
                status().isBadRequest(),
                "Unsupported snapshot field: unknown_field");

        // 3. Category != GRAMMAR, VOCABULARY, TOEIC
        assertCreateDraftFails(
                Map.of("category", "INVALID_CAT", "prompt", "Valid"),
                status().isBadRequest(),
                "category phải là GRAMMAR, VOCABULARY hoặc TOEIC.");

        // 4. Non-null part_number for non-TOEIC
        assertCreateDraftFails(
                Map.of("category", "GRAMMAR", "part_number", 1, "prompt", "Valid"),
                status().isBadRequest(),
                "part_number phải là null đối với non-TOEIC.");

        // 5. Non-array materials
        assertCreateDraftFails(
                Map.of("materials", "not-an-array", "prompt", "Valid"),
                status().isBadRequest(),
                "materials phải là mảng.");

        // 6. Non-array word_sense_ids
        assertCreateDraftFails(
                Map.of("word_sense_ids", "not-an-array", "prompt", "Valid"),
                status().isBadRequest(),
                "word_sense_ids phải là mảng.");

        // 7. word_sense_ids exceeding 10 items
        List<String> tooManySenses = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            tooManySenses.add(UUID.randomUUID().toString());
        }
        assertCreateDraftFails(
                Map.of("word_sense_ids", tooManySenses, "prompt", "Valid"),
                status().isBadRequest(),
                "word_sense_ids phải là mảng tối đa 10 phần tử.");

        // 8. Invalid question_type_code
        assertCreateDraftFails(
                Map.of("question_type_code", "MULTIPLE_CHOICE", "prompt", "Valid"),
                status().isBadRequest(),
                "question_type_code chỉ nhận SINGLE_CHOICE.");

        // 9. Invalid practice_mode
        assertCreateDraftFails(
                Map.of("practice_mode", "SPEED_CHALLENGE", "prompt", "Valid"),
                status().isBadRequest(),
                "practice_mode không hợp lệ.");

        // 10. Invalid difficulty
        assertCreateDraftFails(
                Map.of("difficulty", "EXPERT", "prompt", "Valid"),
                status().isBadRequest(),
                "difficulty chỉ nhận EASY, MEDIUM hoặc HARD.");

        // 11. HTML in prompt
        assertCreateDraftFails(
                Map.of("prompt", "Choose <b>bold</b> word"),
                status().isBadRequest(),
                "prompt không được chứa thẻ HTML.");

        // 12. HTML in explanation
        assertCreateDraftFails(
                Map.of("prompt", "Valid", "explanation", "Explanation with <i>italic</i>"),
                status().isBadRequest(),
                "explanation không được chứa thẻ HTML.");

        // 13. Options > 6
        List<Map<String, Object>> sevenOptions = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            sevenOptions.add(
                    Map.of("id", UUID.randomUUID().toString(), "position", i, "body", "Opt " + i));
        }
        assertCreateDraftFails(
                Map.of("prompt", "Valid", "options", sevenOptions),
                status().isBadRequest(),
                "options phải là mảng từ 2 đến 6 phần tử.");

        // 14. Duplicate positions in options
        assertCreateDraftFails(
                Map.of(
                        "prompt",
                        "Valid",
                        "options",
                        List.of(
                                Map.of(
                                        "id",
                                        UUID.randomUUID().toString(),
                                        "position",
                                        1,
                                        "body",
                                        "Opt 1"),
                                Map.of(
                                        "id",
                                        UUID.randomUUID().toString(),
                                        "position",
                                        1,
                                        "body",
                                        "Opt 2"))),
                status().isBadRequest(),
                "position phải là số nguyên duy nhất từ 1 đến 6.");

        // 15. Invalid position (< 1 or > 6)
        assertCreateDraftFails(
                Map.of(
                        "prompt",
                        "Valid",
                        "options",
                        List.of(
                                Map.of(
                                        "id",
                                        UUID.randomUUID().toString(),
                                        "position",
                                        0,
                                        "body",
                                        "Opt 1"))),
                status().isBadRequest(),
                "position phải là số nguyên duy nhất từ 1 đến 6.");

        // 16. Duplicate option IDs
        UUID sameOptId = UUID.randomUUID();
        assertCreateDraftFails(
                Map.of(
                        "prompt",
                        "Valid",
                        "options",
                        List.of(
                                Map.of("id", sameOptId.toString(), "position", 1, "body", "Opt 1"),
                                Map.of(
                                        "id",
                                        sameOptId.toString(),
                                        "position",
                                        2,
                                        "body",
                                        "Opt 2"))),
                status().isBadRequest(),
                "options.id không được trùng nhau.");

        // 17. Invalid UUID in answer_key.correctOptionId
        assertCreateDraftFails(
                Map.of("prompt", "Valid", "answer_key", Map.of("correctOptionId", "not-a-uuid")),
                status().isBadRequest(),
                "answer_key.correctOptionId phải là UUID.");

        // 18. Duplicate topic_ids
        UUID sameTopicId = UUID.randomUUID();
        assertCreateDraftFails(
                Map.of(
                        "prompt",
                        "Valid",
                        "topic_ids",
                        List.of(sameTopicId.toString(), sameTopicId.toString())),
                status().isBadRequest(),
                "topic_ids không được trùng nhau.");

        // 19. Non-existent sourceId
        CreateQuestionDraftRequest nonExistentSourceReq =
                new CreateQuestionDraftRequest(
                        Map.of("prompt", "Valid"), List.of(UUID.randomUUID()), "Invalid source");
        mockMvc.perform(
                        post("/api/v1/admin/content/questions")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(nonExistentSourceReq)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Cập nhật bản nháp generic PUT với CAS và kiểm soát xung đột phiên bản")
    void updateDraftRevisionQuestionAndCas() throws Exception {
        UUID topicId = createAndPublishTopic("Verbs", "verbs");
        UUID opt1 = UUID.randomUUID();
        UUID opt2 = UUID.randomUUID();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", "GRAMMAR");
        data.put("question_type_code", "SINGLE_CHOICE");
        data.put("practice_mode", "STANDARD");
        data.put("difficulty", "EASY");
        data.put("prompt", "Initial prompt");
        data.put(
                "options",
                List.of(
                        Map.of("id", opt1.toString(), "position", 1, "body", "A"),
                        Map.of("id", opt2.toString(), "position", 2, "body", "B")));
        data.put("answer_key", Map.of("correctOptionId", opt1.toString()));
        data.put("explanation", "Initial explanation");
        data.put("topic_ids", List.of(topicId.toString()));

        CreateQuestionDraftRequest createReq =
                new CreateQuestionDraftRequest(
                        data, List.of(activeSource.getId()), "Initial draft");
        MvcResult createResult =
                mockMvc.perform(
                                post("/api/v1/admin/content/questions")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(createReq)))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode createdJson =
                objectMapper.readTree(createResult.getResponse().getContentAsString());
        UUID revId = UUID.fromString(createdJson.path("latestRevisionId").asText());

        // Update with correct version (0)
        data.put("prompt", "Updated question prompt via CAS");
        UpdateRevisionDraftRequest updateReq =
                new UpdateRevisionDraftRequest(
                        0L, data, List.of(activeSource.getId()), "Updated prompt");

        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/" + revId)
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // Update with wrong expectedVersion (stale version 0 -> 409 Conflict)
        UpdateRevisionDraftRequest staleReq =
                new UpdateRevisionDraftRequest(
                        0L, data, List.of(activeSource.getId()), "Stale update");
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/" + revId)
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(staleReq)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName(
            "Vòng đời xuất bản đầy đủ: Submit -> Approve -> Publish và chiếu nguyên tử vào questions,"
                    + " question_options, question_topics, revision_dependencies")
    void fullPublicationLifecycleAndAtomicProjections() throws Exception {
        UUID topicId = createAndPublishTopic("Tenses", "tenses");
        UUID opt1 = UUID.randomUUID();
        UUID opt2 = UUID.randomUUID();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", "GRAMMAR");
        data.put("part_number", null);
        data.put("question_type_code", "SINGLE_CHOICE");
        data.put("practice_mode", "STANDARD");
        data.put("difficulty", "HARD");
        data.put("prompt", "They ___ here since 2010.");
        data.put("group", null);
        data.put(
                "options",
                List.of(
                        Map.of("id", opt1.toString(), "position", 1, "body", "have lived"),
                        Map.of("id", opt2.toString(), "position", 2, "body", "lived")));
        data.put("answer_key", Map.of("correctOptionId", opt1.toString()));
        data.put(
                "explanation",
                "Present perfect is used with 'since' for actions beginning in the past.");
        data.put("materials", List.of());
        data.put("topic_ids", List.of(topicId.toString()));
        data.put("word_sense_ids", List.of());

        CreateQuestionDraftRequest createReq =
                new CreateQuestionDraftRequest(
                        data, List.of(activeSource.getId()), "Present perfect question");
        MvcResult createResult =
                mockMvc.perform(
                                post("/api/v1/admin/content/questions")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(createReq)))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode createdJson =
                objectMapper.readTree(createResult.getResponse().getContentAsString());
        UUID itemId = UUID.fromString(createdJson.path("id").asText());
        UUID revId = UUID.fromString(createdJson.path("latestRevisionId").asText());

        // 1. Submit
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_REVIEW"));

        // 2. Approve
        ReviewDecisionRequest approveReq = new ReviewDecisionRequest("Nội dung chuẩn xác");
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(approveReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 3. Publish
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        // Assertions on content module
        ContentItem item = itemRepository.findById(itemId).orElseThrow();
        assertThat(item.getPublishedRevisionId()).isEqualTo(revId);

        // Assertions on practice projection
        assertThat(questionRepository.findById(itemId)).isPresent();
        var question = questionRepository.findById(itemId).get();
        assertThat(question.getPrompt()).isEqualTo("They ___ here since 2010.");
        assertThat(question.getDifficulty()).isEqualTo("HARD");
        assertThat(question.getQuestionTypeCode()).isEqualTo("SINGLE_CHOICE");
        assertThat(question.getPracticeMode()).isEqualTo("STANDARD");

        // Options projection
        var options = optionRepository.findByQuestionIdOrderByPositionAsc(itemId);
        assertThat(options).hasSize(2);
        assertThat(options.get(0).getId()).isEqualTo(opt1);
        assertThat(options.get(0).isCorrect()).isTrue();
        assertThat(options.get(0).getBody()).isEqualTo("have lived");
        assertThat(options.get(1).getId()).isEqualTo(opt2);
        assertThat(options.get(1).isCorrect()).isFalse();

        // Topics projection
        questionTopicRepository.flush();
        Integer topicCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM question_topics WHERE question_id = ? AND topic_id = ?",
                        Integer.class,
                        itemId,
                        topicId);
        assertThat(topicCount).isEqualTo(1);

        // Revision dependencies: pinned topic revision!
        ContentItem topicItem = itemRepository.findById(topicId).orElseThrow();
        UUID topicPubRevId = topicItem.getPublishedRevisionId();
        assertThat(topicPubRevId).isNotNull();

        List<RevisionDependency> deps = dependencyRepository.findByIdParentRevisionId(revId);
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).getId().getRelationKey()).isEqualTo("topic:" + topicId);
        assertThat(deps.get(0).getChildRevisionId()).isEqualTo(topicPubRevId);

        // Audit log
        auditLogRepository.flush();
        Integer auditCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_logs WHERE entity_type = 'QUESTION' AND entity_id = ? AND action = 'CONTENT_PUBLISHED'",
                        Integer.class,
                        itemId);
        assertThat(auditCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Từ chối xuất bản nếu chủ điểm bị lưu trữ (archived) hoặc không tồn tại")
    void publicationFailsIfTopicIsArchived() throws Exception {
        UUID topicId = createAndPublishTopic("Archived Topic", "archived-topic");
        UUID opt1 = UUID.randomUUID();
        UUID opt2 = UUID.randomUUID();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", "GRAMMAR");
        data.put("question_type_code", "SINGLE_CHOICE");
        data.put("practice_mode", "STANDARD");
        data.put("difficulty", "EASY");
        data.put("prompt", "Test question for archived topic");
        data.put(
                "options",
                List.of(
                        Map.of("id", opt1.toString(), "position", 1, "body", "A"),
                        Map.of("id", opt2.toString(), "position", 2, "body", "B")));
        data.put("answer_key", Map.of("correctOptionId", opt1.toString()));
        data.put("explanation", "Test explanation");
        data.put("topic_ids", List.of(topicId.toString()));

        CreateQuestionDraftRequest createReq =
                new CreateQuestionDraftRequest(
                        data, List.of(activeSource.getId()), "Draft with topic");
        MvcResult createResult =
                mockMvc.perform(
                                post("/api/v1/admin/content/questions")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(createReq)))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode createdJson =
                objectMapper.readTree(createResult.getResponse().getContentAsString());
        UUID itemId = UUID.fromString(createdJson.path("id").asText());
        UUID revId = UUID.fromString(createdJson.path("latestRevisionId").asText());

        // Submit & Approve
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        // Archive topic
        ArchiveItemRequest archiveReq = new ArchiveItemRequest("Chủ điểm bị gỡ");
        mockMvc.perform(
                        post("/api/v1/admin/content/items/" + topicId + "/archive")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(archiveReq)))
                .andExpect(status().isOk());

        // Publish Question -> must fail with 400
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.detail")
                                .value("topic_ids phải tham chiếu chủ điểm đã xuất bản."));

        // Assert: revision remains APPROVED (not published)
        ContentRevision rev = revisionRepository.findById(revId).orElseThrow();
        assertThat(rev.getStatus()).isEqualTo("APPROVED");

        // Assert: publishedRevisionId pointer remains null
        ContentItem item = itemRepository.findById(itemId).orElseThrow();
        assertThat(item.getPublishedRevisionId()).isNull();

        // Assert: zero dependencies recorded
        List<RevisionDependency> deps = dependencyRepository.findByIdParentRevisionId(revId);
        assertThat(deps).isEmpty();

        // Assert: source snapshot is null
        assertThat(sourceLinkRepository.findByIdRevisionId(revId).get(0).getSourceSnapshot())
                .isNull();

        // Assert: zero publication audit logs
        auditLogRepository.flush();
        Integer pubAudit =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_logs WHERE entity_type = 'QUESTION' AND entity_id = ? AND action = 'CONTENT_PUBLISHED'",
                        Integer.class,
                        itemId);
        assertThat(pubAudit).isEqualTo(0);

        // Invariant: zero practice projections created
        assertThat(questionRepository.findById(itemId)).isEmpty();
        assertThat(optionRepository.findByQuestionIdOrderByPositionAsc(itemId)).isEmpty();
        Integer topicCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM question_topics WHERE question_id = ?",
                        Integer.class,
                        itemId);
        assertThat(topicCount).isEqualTo(0);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName(
            "AC16: Kiểm tra hợp lệ trước (validate / preview) là read-only, 0-mutation trên toàn bộ DB")
    void previewValidationZeroMutations() throws Exception {
        UUID[] ids =
                transactionTemplate.execute(
                        status -> {
                            try {
                                UUID topicId =
                                        createAndPublishTopic("Preview Topic", "preview-topic");
                                UUID opt1 = UUID.randomUUID();
                                UUID opt2 = UUID.randomUUID();

                                Map<String, Object> data = new LinkedHashMap<>();
                                data.put("category", "GRAMMAR");
                                data.put("question_type_code", "SINGLE_CHOICE");
                                data.put("practice_mode", "STANDARD");
                                data.put("difficulty", "EASY");
                                data.put("prompt", "Preview question test");
                                data.put(
                                        "options",
                                        List.of(
                                                Map.of(
                                                        "id",
                                                        opt1.toString(),
                                                        "position",
                                                        1,
                                                        "body",
                                                        "Option A"),
                                                Map.of(
                                                        "id",
                                                        opt2.toString(),
                                                        "position",
                                                        2,
                                                        "body",
                                                        "Option B")));
                                data.put("answer_key", Map.of("correctOptionId", opt1.toString()));
                                data.put("explanation", "Preview explanation");
                                data.put("topic_ids", List.of(topicId.toString()));

                                CreateQuestionDraftRequest createReq =
                                        new CreateQuestionDraftRequest(
                                                data,
                                                List.of(activeSource.getId()),
                                                "Preview test draft");
                                MvcResult createResult =
                                        mockMvc.perform(
                                                        post("/api/v1/admin/content/questions")
                                                                .with(
                                                                        user(
                                                                                UserPrincipal
                                                                                        .create(
                                                                                                contentAdminUser)))
                                                                .with(csrf())
                                                                .contentType(
                                                                        MediaType.APPLICATION_JSON)
                                                                .content(
                                                                        objectMapper
                                                                                .writeValueAsString(
                                                                                        createReq)))
                                                .andExpect(status().isCreated())
                                                .andReturn();

                                JsonNode createdJson =
                                        objectMapper.readTree(
                                                createResult.getResponse().getContentAsString());
                                UUID itemId = UUID.fromString(createdJson.path("id").asText());
                                UUID revId =
                                        UUID.fromString(
                                                createdJson.path("latestRevisionId").asText());

                                // Invalid question draft (pinning non-existent topic)
                                Map<String, Object> invalidData = new LinkedHashMap<>(data);
                                invalidData.put("prompt", "Invalid topic question");
                                invalidData.put("topic_ids", List.of(UUID.randomUUID().toString()));
                                CreateQuestionDraftRequest invReq =
                                        new CreateQuestionDraftRequest(
                                                invalidData,
                                                List.of(activeSource.getId()),
                                                "Invalid topic draft");
                                MvcResult invResult =
                                        mockMvc.perform(
                                                        post("/api/v1/admin/content/questions")
                                                                .with(
                                                                        user(
                                                                                UserPrincipal
                                                                                        .create(
                                                                                                contentAdminUser)))
                                                                .with(csrf())
                                                                .contentType(
                                                                        MediaType.APPLICATION_JSON)
                                                                .content(
                                                                        objectMapper
                                                                                .writeValueAsString(
                                                                                        invReq)))
                                                .andExpect(status().isCreated())
                                                .andReturn();
                                JsonNode invJson =
                                        objectMapper.readTree(
                                                invResult.getResponse().getContentAsString());
                                UUID invItemId = UUID.fromString(invJson.path("id").asText());
                                UUID invRevId =
                                        UUID.fromString(invJson.path("latestRevisionId").asText());

                                return new UUID[] {topicId, itemId, revId, invItemId, invRevId};
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });

        UUID topicId = ids[0];
        UUID validItemId = ids[1];
        UUID validRevId = ids[2];
        UUID invalidItemId = ids[3];
        UUID invalidRevId = ids[4];

        try {
            // Record DB state BEFORE validate (directly from DB outside transaction)
            Integer auditCountBefore =
                    jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);
            Integer depCountBefore =
                    jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM revision_dependencies", Integer.class);
            long questionsBefore = questionRepository.count();
            long optionsBefore = optionRepository.count();
            long topicsBefore = questionTopicRepository.count();
            long revisionsBefore = revisionRepository.count();
            long sourceLinksBefore = sourceLinkRepository.count();

            ContentRevision validRevBefore = revisionRepository.findById(validRevId).orElseThrow();
            long validVersionBefore = validRevBefore.getVersion();
            String validStatusBefore = validRevBefore.getStatus();
            String validSnapshotBefore = validRevBefore.getSnapshot();

            ContentRevision invRevBefore = revisionRepository.findById(invalidRevId).orElseThrow();
            long invVersionBefore = invRevBefore.getVersion();
            String invStatusBefore = invRevBefore.getStatus();
            String invSnapshotBefore = invRevBefore.getSnapshot();

            // 1. Call validate on VALID revision
            mockMvc.perform(
                            post("/api/v1/admin/content/revisions/" + validRevId + "/validate")
                                    .with(user(adminPrincipal()))
                                    .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(true))
                    .andExpect(jsonPath("$.errors").isEmpty());

            // 2. Call validate on INVALID revision
            mockMvc.perform(
                            post("/api/v1/admin/content/revisions/" + invalidRevId + "/validate")
                                    .with(user(adminPrincipal()))
                                    .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(false))
                    .andExpect(jsonPath("$.errors").isNotEmpty());

            // Assertions on commit boundary (directly against PostgreSQL outside transaction):
            Integer auditCountAfter =
                    jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);
            Integer depCountAfter =
                    jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM revision_dependencies", Integer.class);

            assertThat(auditCountAfter).isEqualTo(auditCountBefore);
            assertThat(depCountAfter).isEqualTo(depCountBefore);
            assertThat(questionRepository.count()).isEqualTo(questionsBefore);
            assertThat(optionRepository.count()).isEqualTo(optionsBefore);
            assertThat(questionTopicRepository.count()).isEqualTo(topicsBefore);
            assertThat(revisionRepository.count()).isEqualTo(revisionsBefore);
            assertThat(sourceLinkRepository.count()).isEqualTo(sourceLinksBefore);

            // Check revision (version/status/snapshot)
            ContentRevision validRevAfter = revisionRepository.findById(validRevId).orElseThrow();
            assertThat(validRevAfter.getVersion()).isEqualTo(validVersionBefore);
            assertThat(validRevAfter.getStatus()).isEqualTo(validStatusBefore);
            assertThat(validRevAfter.getSnapshot()).isEqualTo(validSnapshotBefore);

            ContentRevision invRevAfter = revisionRepository.findById(invalidRevId).orElseThrow();
            assertThat(invRevAfter.getVersion()).isEqualTo(invVersionBefore);
            assertThat(invRevAfter.getStatus()).isEqualTo(invStatusBefore);
            assertThat(invRevAfter.getSnapshot()).isEqualTo(invSnapshotBefore);

            // Check publishedRevisionId pointer remains null
            assertThat(itemRepository.findById(validItemId).orElseThrow().getPublishedRevisionId())
                    .isNull();
            assertThat(
                            itemRepository
                                    .findById(invalidItemId)
                                    .orElseThrow()
                                    .getPublishedRevisionId())
                    .isNull();

            // Check source snapshot remains null
            assertThat(
                            sourceLinkRepository
                                    .findByIdRevisionId(validRevId)
                                    .get(0)
                                    .getSourceSnapshot())
                    .isNull();
            assertThat(
                            sourceLinkRepository
                                    .findByIdRevisionId(invalidRevId)
                                    .get(0)
                                    .getSourceSnapshot())
                    .isNull();
        } finally {
            transactionTemplate.execute(
                    status -> {
                        jdbcTemplate.update("DELETE FROM grammar_topics WHERE id = ?", topicId);
                        jdbcTemplate.update(
                                "UPDATE content_items SET is_archived = true, archived_at = CURRENT_TIMESTAMP WHERE id IN (?, ?, ?)",
                                topicId,
                                validItemId,
                                invalidItemId);
                        return null;
                    });
        }
    }

    @Test
    @DisplayName(
            "Nhân bản (clone) câu hỏi đã xuất bản giữ ổn định option IDs và phân tách revision dependencies")
    void clonePublishedQuestionMaintainsOptionStability() throws Exception {
        UUID topicId = createAndPublishTopic("Cloning Topic", "cloning-topic");
        UUID opt1 = UUID.randomUUID();
        UUID opt2 = UUID.randomUUID();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", "GRAMMAR");
        data.put("question_type_code", "SINGLE_CHOICE");
        data.put("practice_mode", "STANDARD");
        data.put("difficulty", "EASY");
        data.put("prompt", "Prompt version 1");
        data.put(
                "options",
                List.of(
                        Map.of("id", opt1.toString(), "position", 1, "body", "Body 1"),
                        Map.of("id", opt2.toString(), "position", 2, "body", "Body 2")));
        data.put("answer_key", Map.of("correctOptionId", opt1.toString()));
        data.put("explanation", "Explanation 1");
        data.put("topic_ids", List.of(topicId.toString()));

        CreateQuestionDraftRequest createReq =
                new CreateQuestionDraftRequest(data, List.of(activeSource.getId()), "Draft v1");
        MvcResult createResult =
                mockMvc.perform(
                                post("/api/v1/admin/content/questions")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(createReq)))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode createdJson =
                objectMapper.readTree(createResult.getResponse().getContentAsString());
        UUID itemId = UUID.fromString(createdJson.path("id").asText());
        UUID rev1Id = UUID.fromString(createdJson.path("latestRevisionId").asText());

        // Publish v1
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + rev1Id + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + rev1Id + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + rev1Id + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        // Clone to v2
        CreateRevisionFromPublishedRequest cloneReq =
                new CreateRevisionFromPublishedRequest(rev1Id, 1L, "Tạo bản v2 cập nhật prompt");
        MvcResult cloneResult =
                mockMvc.perform(
                                post("/api/v1/admin/content/items/" + itemId + "/revisions")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(cloneReq)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.versionNo").value(2))
                        .andExpect(jsonPath("$.status").value("DRAFT"))
                        .andReturn();

        JsonNode cloneJson = objectMapper.readTree(cloneResult.getResponse().getContentAsString());
        UUID rev2Id = UUID.fromString(cloneJson.path("revisionId").asText());

        // Update prompt in v2
        data.put("prompt", "Prompt version 2 updated");
        UpdateRevisionDraftRequest updateReq =
                new UpdateRevisionDraftRequest(
                        0L, data, List.of(activeSource.getId()), "Update prompt v2");
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/" + rev2Id)
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk());

        // Publish v2
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + rev2Id + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + rev2Id + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + rev2Id + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        // Assert option stability: option IDs remain opt1 and opt2 in question_options
        var optionsV2 = optionRepository.findByQuestionIdOrderByPositionAsc(itemId);
        assertThat(optionsV2).hasSize(2);
        assertThat(optionsV2.get(0).getId()).isEqualTo(opt1);
        assertThat(optionsV2.get(1).getId()).isEqualTo(opt2);

        // Dependency preservation: v1 and v2 each have their own independent dependency record
        assertThat(dependencyRepository.findByIdParentRevisionId(rev1Id)).hasSize(1);
        assertThat(dependencyRepository.findByIdParentRevisionId(rev2Id)).hasSize(1);
    }

    @Test
    @DisplayName(
            "Admin content listing: GET /items?contentType=QUESTION trả title từ prompt, domain=GRAMMAR loại trừ QUESTION")
    void adminContentListingReturnsQuestionPrompt() throws Exception {
        UUID topicId = createAndPublishTopic("Listing Topic", "listing-topic");
        String uniquePrompt = "UniqueQuestionPrompt-" + UUID.randomUUID();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", "GRAMMAR");
        data.put("prompt", uniquePrompt);
        data.put("difficulty", "HARD");

        CreateQuestionDraftRequest createReq =
                new CreateQuestionDraftRequest(
                        data, List.of(activeSource.getId()), "Listing test question");
        mockMvc.perform(
                        post("/api/v1/admin/content/questions")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated());

        // 1. GET with contentType=QUESTION: must return the item with title = prompt
        mockMvc.perform(
                        get("/api/v1/admin/content/items?contentType=QUESTION")
                                .with(user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.title == '" + uniquePrompt + "')]").exists())
                .andExpect(jsonPath("$.items[?(@.type == 'QUESTION')]").exists());

        // 2. GET with domain=GRAMMAR: must NOT contain QUESTION (as defined by contract)
        mockMvc.perform(
                        get("/api/v1/admin/content/items?domain=GRAMMAR")
                                .with(user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.type == 'QUESTION')]").doesNotExist());

        // 3. Search by prompt text
        mockMvc.perform(
                        get("/api/v1/admin/content/items?search=" + uniquePrompt)
                                .with(user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value(uniquePrompt));
    }

    @Test
    @DisplayName(
            "Người học nhận câu hỏi không để lộ đáp án/giải thích trước khi nộp, nhận giải thích sau khi nộp")
    void learnerPracticeAttemptSealsAnswersAndSelectsQuestion() throws Exception {
        UUID topicId = createAndPublishTopic("Learner Topic", "learner-topic");
        UUID opt1 = UUID.randomUUID();
        UUID opt2 = UUID.randomUUID();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", "GRAMMAR");
        data.put("question_type_code", "SINGLE_CHOICE");
        data.put("practice_mode", "STANDARD");
        data.put("difficulty", "MEDIUM");
        data.put("prompt", "What is the capital of England?");
        data.put(
                "options",
                List.of(
                        Map.of("id", opt1.toString(), "position", 1, "body", "London"),
                        Map.of("id", opt2.toString(), "position", 2, "body", "Manchester")));
        data.put("answer_key", Map.of("correctOptionId", opt1.toString()));
        data.put("explanation", "London is the capital of England and the United Kingdom.");
        data.put("topic_ids", List.of(topicId.toString()));

        UUID questionId = createAndPublishQuestion(data);

        // Learner starts practice for this topic
        UUID requestId = UUID.randomUUID();
        MvcResult startResult =
                mockMvc.perform(
                                post("/api/v1/grammar/topics/" + topicId + "/attempts")
                                        .with(user(learnerPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"requestId\":\"" + requestId + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode attemptJson =
                objectMapper.readTree(startResult.getResponse().getContentAsString());
        UUID attemptId = UUID.fromString(attemptJson.path("id").asText());
        JsonNode questions = attemptJson.path("questions");
        assertThat(questions).isNotEmpty();

        JsonNode q0 = questions.get(0);
        assertThat(q0.path("prompt").asText()).isEqualTo("What is the capital of England?");
        // Sealed snapshot check: explanation and correct flags must be null before submission
        assertThat(q0.path("explanation").isNull()).isTrue();
        assertThat(q0.path("correct").isNull()).isTrue();
        assertThat(q0.has("answer_key")).isFalse();
        UUID selectedOptionId = null;
        for (JsonNode opt : q0.path("options")) {
            assertThat(opt.path("correct").isNull()).isTrue();
            if ("London".equals(opt.path("body").asText())) {
                selectedOptionId = UUID.fromString(opt.path("id").asText());
            }
        }
        assertThat(selectedOptionId).isNotNull();

        // Kiểm chứng gửi ID gốc từ authoring bị từ chối 400 (do attempt snapshot sinh ID riêng)
        mockMvc.perform(
                        put("/api/v1/attempts/" + attemptId + "/answers/1")
                                .with(user(learnerPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"optionId\":\"" + opt1 + "\"}"))
                .andExpect(status().isBadRequest());

        // Learner answers với optionId từ attempt DTO
        mockMvc.perform(
                        put("/api/v1/attempts/" + attemptId + "/answers/1")
                                .with(user(learnerPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"optionId\":\"" + selectedOptionId + "\"}"))
                .andExpect(status().isOk());

        // Learner submits
        MvcResult submitResult =
                mockMvc.perform(
                                post("/api/v1/attempts/" + attemptId + "/submit")
                                        .with(user(learnerPrincipal()))
                                        .with(csrf()))
                        .andExpect(status().isOk())
                        .andReturn();

        JsonNode submitJson =
                objectMapper.readTree(submitResult.getResponse().getContentAsString());
        assertThat(submitJson.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(submitJson.path("score").asInt()).isEqualTo(100);

        JsonNode submittedQ0 = submitJson.path("questions").get(0);
        assertThat(submittedQ0.path("correct").asBoolean()).isTrue();
        assertThat(submittedQ0.path("explanation").asText())
                .isEqualTo("London is the capital of England and the United Kingdom.");
    }

    @Test
    @DisplayName(
            "Cùng một câu hỏi thuộc nhiều topic nhưng khi selection bài tập hỗn hợp chỉ chọn duy nhất 1 lần (distinct)")
    void multiTopicDistinctSelection() throws Exception {
        UUID topic1 = createAndPublishTopic("Topic Alpha", "topic-alpha");
        UUID topic2 = createAndPublishTopic("Topic Beta", "topic-beta");
        UUID opt1 = UUID.randomUUID();
        UUID opt2 = UUID.randomUUID();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", "GRAMMAR");
        data.put("question_type_code", "SINGLE_CHOICE");
        data.put("practice_mode", "STANDARD");
        data.put("difficulty", "EASY");
        data.put("prompt", "Multi-topic shared question prompt");
        data.put(
                "options",
                List.of(
                        Map.of("id", opt1.toString(), "position", 1, "body", "Option 1"),
                        Map.of("id", opt2.toString(), "position", 2, "body", "Option 2")));
        data.put("answer_key", Map.of("correctOptionId", opt1.toString()));
        data.put("explanation", "Shared explanation");
        data.put("topic_ids", List.of(topic1.toString(), topic2.toString()));

        UUID questionId = createAndPublishQuestion(data);

        // Assert: question_topics contains both topic1 and topic2
        questionTopicRepository.flush();
        Integer countT1 =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM question_topics WHERE question_id = ? AND topic_id = ?",
                        Integer.class,
                        questionId,
                        topic1);
        Integer countT2 =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM question_topics WHERE question_id = ? AND topic_id = ?",
                        Integer.class,
                        questionId,
                        topic2);
        assertThat(countT1).isEqualTo(1);
        assertThat(countT2).isEqualTo(1);

        // Assert: revision_dependencies pins exact published revision of each topic
        UUID qRevId = itemRepository.findById(questionId).orElseThrow().getPublishedRevisionId();
        UUID top1RevId = itemRepository.findById(topic1).orElseThrow().getPublishedRevisionId();
        UUID top2RevId = itemRepository.findById(topic2).orElseThrow().getPublishedRevisionId();

        List<RevisionDependency> deps = dependencyRepository.findByIdParentRevisionId(qRevId);
        assertThat(deps).hasSize(2);
        assertThat(
                        deps.stream()
                                .filter(d -> d.getId().getRelationKey().equals("topic:" + topic1))
                                .findFirst()
                                .orElseThrow()
                                .getChildRevisionId())
                .isEqualTo(top1RevId);
        assertThat(
                        deps.stream()
                                .filter(d -> d.getId().getRelationKey().equals("topic:" + topic2))
                                .findFirst()
                                .orElseThrow()
                                .getChildRevisionId())
                .isEqualTo(top2RevId);

        // Topic 1 creates and publishes v2: ensure question's existing published revision keeps
        // pinning top1RevId (v1)
        CreateRevisionFromPublishedRequest cloneTopicReq =
                new CreateRevisionFromPublishedRequest(top1RevId, 1L, "Topic Alpha v2");
        MvcResult cloneTopicRes =
                mockMvc.perform(
                                post("/api/v1/admin/content/items/" + topic1 + "/revisions")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(cloneTopicReq)))
                        .andExpect(status().isCreated())
                        .andReturn();
        UUID top1Rev2Id =
                UUID.fromString(
                        objectMapper
                                .readTree(cloneTopicRes.getResponse().getContentAsString())
                                .path("revisionId")
                                .asText());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + top1Rev2Id + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + top1Rev2Id + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new ReviewDecisionRequest("Approved Topic v2"))))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + top1Rev2Id + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        // Topic 1 latest published revision is now top1Rev2Id
        assertThat(itemRepository.findById(topic1).orElseThrow().getPublishedRevisionId())
                .isEqualTo(top1Rev2Id);

        // Question revision dependency for qRevId MUST STILL PIN top1RevId (v1)!
        List<RevisionDependency> depsAfter = dependencyRepository.findByIdParentRevisionId(qRevId);
        assertThat(depsAfter).hasSize(2);
        assertThat(
                        depsAfter.stream()
                                .filter(d -> d.getId().getRelationKey().equals("topic:" + topic1))
                                .findFirst()
                                .orElseThrow()
                                .getChildRevisionId())
                .isEqualTo(top1RevId);

        // Start mixed attempt selecting both Topic Alpha and Topic Beta
        UUID requestId = UUID.randomUUID();
        Map<String, Object> mixedBody =
                Map.of(
                        "requestId", requestId.toString(),
                        "topicIds", List.of(topic1.toString(), topic2.toString()),
                        "questionCount", 10);

        MvcResult mixedResult =
                mockMvc.perform(
                                post("/api/v1/grammar/attempts/mixed")
                                        .with(user(learnerPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(mixedBody)))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode attemptJson =
                objectMapper.readTree(mixedResult.getResponse().getContentAsString());
        JsonNode questions = attemptJson.path("questions");

        // Question must appear exactly ONCE
        long matchCount = 0;
        for (JsonNode q : questions) {
            if ("Multi-topic shared question prompt".equals(q.path("prompt").asText())) {
                matchCount++;
            }
        }
        assertThat(matchCount).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "Bảo mật: Từ chối người dùng ẩn danh (401), vai trò LEARNER (403), thiếu CSRF (403)")
    void securityAndRoleEnforcement() throws Exception {
        CreateQuestionDraftRequest req =
                new CreateQuestionDraftRequest(
                        Map.of("prompt", "Security check"), List.of(), "Sec test");
        String content = objectMapper.writeValueAsString(req);

        // 1. Anonymous -> 401
        mockMvc.perform(
                        post("/api/v1/admin/content/questions")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(content))
                .andExpect(status().isUnauthorized());

        // 2. LEARNER role -> 403
        mockMvc.perform(
                        post("/api/v1/admin/content/questions")
                                .with(user(learnerPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(content))
                .andExpect(status().isForbidden());

        // 3. Missing CSRF -> 403
        mockMvc.perform(
                        post("/api/v1/admin/content/questions")
                                .with(user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(content))
                .andExpect(status().isForbidden());
    }

    // --- Helpers ---

    private void assertCreateDraftFails(
            Map<String, Object> data,
            org.springframework.test.web.servlet.ResultMatcher expectedStatus,
            String expectedDetailMessage)
            throws Exception {
        CreateQuestionDraftRequest request =
                new CreateQuestionDraftRequest(
                        data, List.of(activeSource.getId()), "Validation test draft");
        mockMvc.perform(
                        post("/api/v1/admin/content/questions")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(expectedStatus)
                .andExpect(jsonPath("$.detail").value(expectedDetailMessage));
    }

    private UUID createAndPublishTopic(String name, String slug) throws Exception {
        String uniqueSlug = slug + "-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> topicData =
                Map.of(
                        "name",
                        name,
                        "slug",
                        uniqueSlug,
                        "description",
                        "Topic description for " + name,
                        "difficulty",
                        "BASIC",
                        "sort_order",
                        1);
        CreateGrammarDraftRequest req =
                new CreateGrammarDraftRequest(
                        topicData, List.of(activeSource.getId()), "Topic creation");
        MvcResult res =
                mockMvc.perform(
                                post("/api/v1/admin/content/grammar/topics")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(req)))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        UUID itemId = UUID.fromString(json.path("id").asText());
        UUID revId = UUID.fromString(json.path("latestRevisionId").asText());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        return itemId;
    }

    private UUID createAndPublishQuestion(Map<String, Object> data) throws Exception {
        CreateQuestionDraftRequest createReq =
                new CreateQuestionDraftRequest(
                        data, List.of(activeSource.getId()), "Publish helper question");
        MvcResult createResult =
                mockMvc.perform(
                                post("/api/v1/admin/content/questions")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(createReq)))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode createdJson =
                objectMapper.readTree(createResult.getResponse().getContentAsString());
        UUID itemId = UUID.fromString(createdJson.path("id").asText());
        UUID revId = UUID.fromString(createdJson.path("latestRevisionId").asText());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        return itemId;
    }

    private void assertPublishFails(Map<String, Object> data, String expectedDetail)
            throws Exception {
        CreateQuestionDraftRequest createReq =
                new CreateQuestionDraftRequest(
                        data, List.of(activeSource.getId()), "Publish failure test question");
        MvcResult createResult =
                mockMvc.perform(
                                post("/api/v1/admin/content/questions")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(createReq)))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode createdJson =
                objectMapper.readTree(createResult.getResponse().getContentAsString());
        UUID revId = UUID.fromString(createdJson.path("latestRevisionId").asText());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(expectedDetail));
    }

    private UUID createAndPublishWord(String lemma) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lemma", lemma);
        body.put("partOfSpeech", "NOUN");
        body.put("cefrLevel", "B1");
        body.put("meaningVi", "nghĩa " + lemma);
        body.put("definitionEn", "definition of " + lemma);
        body.put("examplesEn", List.of("Example of " + lemma));
        body.put("examplesVi", List.of("Ví dụ của " + lemma));
        body.put("sourceId", activeSource.getId());

        MvcResult res =
                mockMvc.perform(
                                post("/api/v1/admin/content/words")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(body)))
                        .andExpect(status().isCreated())
                        .andReturn();
        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        UUID revId = UUID.fromString(json.path("latestRevisionId").asText());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        ContentRevision rev = revisionRepository.findById(revId).orElseThrow();
        JsonNode snap = objectMapper.readTree(rev.getSnapshot());
        JsonNode data = snap.has("data") ? snap.path("data") : snap;
        return UUID.fromString(data.path("senses").get(0).path("id").asText());
    }

    @Test
    @DisplayName(
            "Soạn và xuất bản câu hỏi VOCABULARY liên kết word_sense_ids, ghim revision_dependencies và chiếu sang question_words")
    void vocabularyQuestionAuthoringAndPublication() throws Exception {
        UUID senseId =
                createAndPublishWord("vacation" + UUID.randomUUID().toString().substring(0, 5));

        Map<String, Object> questionData = new LinkedHashMap<>();
        questionData.put("category", "VOCABULARY");
        questionData.put("question_type_code", "SINGLE_CHOICE");
        questionData.put("practice_mode", "STANDARD");
        questionData.put("difficulty", "MEDIUM");
        questionData.put("prompt", "What does this word mean?");
        questionData.put("explanation", "Vacation means a period of time away from work.");
        questionData.put("word_sense_ids", List.of(senseId.toString()));

        UUID opt1 = UUID.randomUUID();
        UUID opt2 = UUID.randomUUID();
        questionData.put(
                "options",
                List.of(
                        Map.of("id", opt1.toString(), "position", 1, "body", "Holiday/time off"),
                        Map.of("id", opt2.toString(), "position", 2, "body", "Overtime work")));
        questionData.put("answer_key", Map.of("correctOptionId", opt1.toString()));

        UUID questionId = createAndPublishQuestion(questionData);

        // Verify question in read model
        Question question = questionRepository.findById(questionId).orElseThrow();
        assertThat(question.getCategory()).isEqualTo("VOCABULARY");
        assertThat(question.getPartNumber()).isNull();

        // Verify question_words table has link
        Integer wordLinkCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM question_words WHERE question_id = ? AND word_sense_id = ?",
                        Integer.class,
                        questionId,
                        senseId);
        assertThat(wordLinkCount).isEqualTo(1);

        // Verify revision_dependencies pinned word revision
        List<RevisionDependency> deps =
                dependencyRepository.findByIdParentRevisionId(question.getPublishedRevisionId());
        assertThat(deps).anyMatch(d -> d.getId().getRelationKey().equals("word_sense:" + senseId));
    }

    @Test
    @DisplayName(
            "TOEIC options constraints: Part 2 bắt buộc 3 options, Part 1/5 bắt buộc 4 options, non-TOEIC part_number=null")
    void toeicQuestionPartOptionsConstraints() throws Exception {
        // 1. TOEIC Part 2 with 3 options -> succeeds
        var p2Opts = makeOptions(3);
        Map<String, Object> p2Data = new LinkedHashMap<>();
        p2Data.put("category", "TOEIC");
        p2Data.put("part_number", 2);
        p2Data.put("question_type_code", "SINGLE_CHOICE");
        p2Data.put("practice_mode", "STANDARD");
        p2Data.put("difficulty", "EASY");
        p2Data.put("prompt", "Where is the meeting room?");
        p2Data.put("explanation", "It is on the third floor.");
        p2Data.put("options", p2Opts);
        p2Data.put("answer_key", Map.of("correctOptionId", p2Opts.get(0).get("id")));
        UUID p2Id = createAndPublishQuestion(p2Data);
        assertThat(questionRepository.findById(p2Id).get().getPartNumber()).isEqualTo(2);

        // 2. TOEIC Part 2 with 4 options -> fails validation
        var p2InvalidOpts = makeOptions(4);
        Map<String, Object> p2Invalid = new LinkedHashMap<>(p2Data);
        p2Invalid.put("options", p2InvalidOpts);
        p2Invalid.put("answer_key", Map.of("correctOptionId", p2InvalidOpts.get(0).get("id")));
        assertPublishFails(p2Invalid, "TOEIC Part 2 yêu cầu đúng 3 lựa chọn.");

        // 3. TOEIC Part 5 with 4 options -> succeeds
        var p5Opts = makeOptions(4);
        Map<String, Object> p5Data = new LinkedHashMap<>();
        p5Data.put("category", "TOEIC");
        p5Data.put("part_number", 5);
        p5Data.put("question_type_code", "SINGLE_CHOICE");
        p5Data.put("practice_mode", "STANDARD");
        p5Data.put("difficulty", "HARD");
        p5Data.put("prompt", "The committee has announced _____ decision.");
        p5Data.put("explanation", "Possessive pronoun 'its' is needed.");
        p5Data.put("options", p5Opts);
        p5Data.put("answer_key", Map.of("correctOptionId", p5Opts.get(0).get("id")));
        UUID p5Id = createAndPublishQuestion(p5Data);
        assertThat(questionRepository.findById(p5Id).get().getPartNumber()).isEqualTo(5);

        // 4. TOEIC Part 5 with 3 options -> fails validation
        var p5InvalidOpts = makeOptions(3);
        Map<String, Object> p5Invalid = new LinkedHashMap<>(p5Data);
        p5Invalid.put("options", p5InvalidOpts);
        p5Invalid.put("answer_key", Map.of("correctOptionId", p5InvalidOpts.get(0).get("id")));
        assertPublishFails(p5Invalid, "TOEIC Part 5 yêu cầu đúng 4 lựa chọn.");
    }

    @Test
    @DisplayName(
            "Bộ chọn câu hỏi ngữ pháp (pickQuestions/countVisibleByTopicId) lọc chính xác category='GRAMMAR', không lọt VOCABULARY hoặc TOEIC")
    void practiceGrammarFilterDoesNotPickVocabOrToeic() throws Exception {
        UUID topicId = createAndPublishTopic("Tenses Topic", "tenses-topic");
        UUID senseId = createAndPublishWord("learn" + UUID.randomUUID().toString().substring(0, 5));

        // 1. Create and publish 1 GRAMMAR question with topicId
        var grammarOpts = makeOptions(2);
        Map<String, Object> grammarData = new LinkedHashMap<>();
        grammarData.put("category", "GRAMMAR");
        grammarData.put("question_type_code", "SINGLE_CHOICE");
        grammarData.put("practice_mode", "STANDARD");
        grammarData.put("difficulty", "MEDIUM");
        grammarData.put("prompt", "He _____ to school every day.");
        grammarData.put("explanation", "Present simple with third person singular: goes.");
        grammarData.put("topic_ids", List.of(topicId.toString()));
        grammarData.put("options", grammarOpts);
        grammarData.put("answer_key", Map.of("correctOptionId", grammarOpts.get(0).get("id")));
        UUID grammarQId = createAndPublishQuestion(grammarData);

        // 2. Create and publish 1 VOCABULARY question
        var vocabOpts = makeOptions(2);
        Map<String, Object> vocabData = new LinkedHashMap<>();
        vocabData.put("category", "VOCABULARY");
        vocabData.put("question_type_code", "SINGLE_CHOICE");
        vocabData.put("practice_mode", "STANDARD");
        vocabData.put("difficulty", "MEDIUM");
        vocabData.put("prompt", "Vocab question prompt");
        vocabData.put("explanation", "Vocab question explanation");
        vocabData.put("word_sense_ids", List.of(senseId.toString()));
        vocabData.put("options", vocabOpts);
        vocabData.put("answer_key", Map.of("correctOptionId", vocabOpts.get(0).get("id")));
        createAndPublishQuestion(vocabData);

        // 3. Create and publish 1 TOEIC question tagged with topicId (TOEIC allows optional
        // topic_ids)
        var toeicOpts = makeOptions(4);
        Map<String, Object> toeicData = new LinkedHashMap<>();
        toeicData.put("category", "TOEIC");
        toeicData.put("part_number", 5);
        toeicData.put("question_type_code", "SINGLE_CHOICE");
        toeicData.put("practice_mode", "STANDARD");
        toeicData.put("difficulty", "MEDIUM");
        toeicData.put("prompt", "TOEIC question with topic tag");
        toeicData.put("explanation", "TOEIC explanation");
        toeicData.put("topic_ids", List.of(topicId.toString()));
        toeicData.put("options", toeicOpts);
        toeicData.put("answer_key", Map.of("correctOptionId", toeicOpts.get(0).get("id")));
        createAndPublishQuestion(toeicData);

        // Verify pickQuestions and countVisibleByTopicId
        List<QuestionRepository.PickedQuestion> picked =
                questionRepository.pickQuestions(List.of(topicId), 10);
        assertThat(picked).hasSize(1);
        assertThat(picked.get(0).getQuestionId()).isEqualTo(grammarQId);
        assertThat(picked.get(0).getTopicId()).isEqualTo(topicId);

        long count = questionRepository.countVisibleByTopicId(topicId);
        assertThat(count).isEqualTo(1L);
    }

    private static List<Map<String, Object>> makeOptions(int count) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(
                    Map.of(
                            "id",
                            UUID.randomUUID().toString(),
                            "position",
                            i,
                            "body",
                            "Option " + i));
        }
        return list;
    }
}
