package com.masterenglish.api.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.infrastructure.security.UserPrincipal;
import com.masterenglish.api.modules.admin.dto.request.CreateGrammarDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.ReviewDecisionRequest;
import com.masterenglish.api.modules.admin.dto.request.UpdateRevisionDraftRequest;
import com.masterenglish.api.modules.content.dto.response.ContentRevisionResponse;
import com.masterenglish.api.modules.content.entity.ContentItem;
import com.masterenglish.api.modules.content.entity.ContentRevision;
import com.masterenglish.api.modules.content.entity.ContentSource;
import com.masterenglish.api.modules.content.repository.ContentItemRepository;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceLinkRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceRepository;
import com.masterenglish.api.modules.content.repository.RevisionDependencyRepository;
import com.masterenglish.api.modules.content.service.ContentAuthoringService;
import com.masterenglish.api.modules.grammar.repository.GrammarLessonRepository;
import com.masterenglish.api.modules.grammar.repository.GrammarTopicRepository;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.RoleRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
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
@org.junit.jupiter.api.condition.EnabledIf("isDockerAvailable")
class GrammarAuthoringIntegrationTests {

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
    @Autowired private GrammarTopicRepository topicRepository;
    @Autowired private GrammarLessonRepository lessonRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ContentAuthoringService authoringService;

    private boolean awaitBlockedBy(Long waiterPid, Long blockerPid, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Boolean blocked =
                    jdbcTemplate.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM unnest(pg_blocking_pids(CAST(? AS integer))) AS blocker(pid) WHERE blocker.pid = CAST(? AS integer))",
                            Boolean.class,
                            waiterPid,
                            blockerPid);
            if (Boolean.TRUE.equals(blocked)) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    private MockMvc mockMvc;
    private User contentAdminUser;
    private User secondContentAdminUser;
    private User learnerUser;
    private ContentSource defaultSource;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Role contentRole = roleRepository.findByCode(RoleCode.CONTENT_ADMIN).orElseThrow();
        Role learnerRole = roleRepository.findByCode(RoleCode.LEARNER).orElseThrow();

        String testId = UUID.randomUUID().toString().substring(0, 8);
        contentAdminUser = new User("admin-" + testId + "@test.com", "hash", "Content", "Admin");
        contentAdminUser.addRole(contentRole);
        contentAdminUser.markEmailVerified(Instant.now());
        contentAdminUser = userRepository.saveAndFlush(contentAdminUser);

        secondContentAdminUser =
                new User("admin2-" + testId + "@test.com", "hash", "Content2", "Admin2");
        secondContentAdminUser.addRole(contentRole);
        secondContentAdminUser.markEmailVerified(Instant.now());
        secondContentAdminUser = userRepository.saveAndFlush(secondContentAdminUser);

        learnerUser = new User("learner-" + testId + "@test.com", "hash", "Learner", "User");
        learnerUser.addRole(learnerRole);
        learnerUser.markEmailVerified(Instant.now());
        learnerUser = userRepository.saveAndFlush(learnerUser);

        defaultSource =
                sourceRepository.saveAndFlush(
                        new ContentSource(
                                UUID.randomUUID(),
                                "English Grammar in Use 5th",
                                "EXTERNAL",
                                "https://cambridge.org/grammar",
                                "Raymond Murphy",
                                "EDITORIAL_CITATION",
                                null,
                                "Standard citation",
                                "Educational license",
                                "ACTIVE",
                                contentAdminUser.getId()));
    }

    private UserPrincipal adminPrincipal() {
        return UserPrincipal.create(contentAdminUser);
    }

    private UserPrincipal learnerPrincipal() {
        return UserPrincipal.create(learnerUser);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // =========================================================================
    // G1 / AC04, AC24: Create Grammar Topic & Lesson Drafts + Source Boundaries (GC2)
    // =========================================================================

    @Test
    @DisplayName(
            "G1 / GC2: Tạo Topic Draft thành công với partial draft, duplicate sourceIds được de-duplicate")
    void createGrammarTopicDraftSuccess() throws Exception {
        Map<String, Object> topicData = new HashMap<>();
        topicData.put("name", "Present Tenses");
        topicData.put("difficulty", "BASIC");

        CreateGrammarDraftRequest request =
                new CreateGrammarDraftRequest(
                        topicData,
                        List.of(defaultSource.getId(), defaultSource.getId()),
                        "Initial grammar topic draft");

        String responseJson =
                mockMvc.perform(
                                post("/api/v1/admin/content/grammar/topics")
                                        .with(
                                                SecurityMockMvcRequestPostProcessors.user(
                                                        adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.id").isNotEmpty())
                        .andExpect(jsonPath("$.type").value("GRAMMAR_TOPIC"))
                        .andExpect(jsonPath("$.status").value("DRAFT"))
                        .andExpect(jsonPath("$.versionNo").value(1))
                        .andExpect(jsonPath("$.title").value("Present Tenses"))
                        .andExpect(jsonPath("$.level").value("BASIC"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        Map<String, Object> response = toMap(responseJson);
        UUID itemId = UUID.fromString((String) response.get("id"));

        ContentItem item = itemRepository.findById(itemId).orElseThrow();
        assertThat(item.getContentType()).isEqualTo("GRAMMAR_TOPIC");
        assertThat(item.getPublishedRevisionId()).isNull();

        List<ContentRevision> revisions =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(itemId);
        assertThat(revisions).hasSize(1);
        ContentRevision revision = revisions.get(0);
        assertThat(revision.getStatus()).isEqualTo("DRAFT");
        assertThat(revision.getVersionNo()).isEqualTo(1);

        // Verify source links de-duplicated to 1 link
        var links = sourceLinkRepository.findByIdRevisionId(revision.getId());
        assertThat(links).hasSize(1);
        assertThat(links.get(0).getId().getSourceId()).isEqualTo(defaultSource.getId());
    }

    @Test
    @DisplayName(
            "G1 / GC2: Source boundaries - null sourceIds, null element, and non-existent source rollback")
    void createGrammarDraftSourceBoundaries() throws Exception {
        Map<String, Object> topicData = Map.of("name", "Modal Verbs");

        // 1. sourceIds: null -> creates draft with 0 source links
        CreateGrammarDraftRequest nullSourcesReq =
                new CreateGrammarDraftRequest(topicData, null, "Null sources");
        String res1 =
                mockMvc.perform(
                                post("/api/v1/admin/content/grammar/topics")
                                        .with(
                                                SecurityMockMvcRequestPostProcessors.user(
                                                        adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(nullSourcesReq)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID item1Id = UUID.fromString((String) toMap(res1).get("id"));
        UUID rev1Id = UUID.fromString((String) toMap(res1).get("latestRevisionId"));
        assertThat(sourceLinkRepository.findByIdRevisionId(rev1Id)).isEmpty();

        // 2. sourceIds with null element -> handles safely without 500
        CreateGrammarDraftRequest nullElemReq =
                new CreateGrammarDraftRequest(
                        topicData,
                        Arrays.asList(defaultSource.getId(), null),
                        "Null element in sources");
        String res2 =
                mockMvc.perform(
                                post("/api/v1/admin/content/grammar/topics")
                                        .with(
                                                SecurityMockMvcRequestPostProcessors.user(
                                                        adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(nullElemReq)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID rev2Id = UUID.fromString((String) toMap(res2).get("latestRevisionId"));
        assertThat(sourceLinkRepository.findByIdRevisionId(rev2Id)).hasSize(1);

        // 3. Non-existent sourceId -> 400 Bad Request, DB rollback (no orphaned item created)
        UUID fakeSourceId = UUID.randomUUID();
        CreateGrammarDraftRequest missingSourceReq =
                new CreateGrammarDraftRequest(topicData, List.of(fakeSourceId), "Missing source");
        int itemsBefore = itemRepository.findAll().size();
        mockMvc.perform(
                        post("/api/v1/admin/content/grammar/topics")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(missingSourceReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        int itemsAfter = itemRepository.findAll().size();
        assertThat(itemsAfter).isEqualTo(itemsBefore);
    }

    @Test
    @DisplayName("G1: Tạo Lesson Draft thành công với partial draft")
    void createGrammarLessonDraftSuccess() throws Exception {
        Map<String, Object> lessonData = new HashMap<>();
        lessonData.put("title", "Present Simple Overview");
        lessonData.put("position", 1);

        CreateGrammarDraftRequest request =
                new CreateGrammarDraftRequest(
                        lessonData, List.of(defaultSource.getId()), "Initial grammar lesson draft");

        mockMvc.perform(
                        post("/api/v1/admin/content/grammar/lessons")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.type").value("GRAMMAR_LESSON"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.title").value("Present Simple Overview"));
    }

    @Test
    @DisplayName("G1: Auth, Role, CSRF và Nguồn không tồn tại bị từ chối")
    void createGrammarDraftSecurityAndValidation() throws Exception {
        Map<String, Object> topicData = Map.of("name", "Nouns");
        CreateGrammarDraftRequest request =
                new CreateGrammarDraftRequest(topicData, List.of(defaultSource.getId()), "Draft");

        // 401 Unauthorized
        mockMvc.perform(
                        post("/api/v1/admin/content/grammar/topics")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        // 403 Forbidden for LEARNER
        mockMvc.perform(
                        post("/api/v1/admin/content/grammar/topics")
                                .with(SecurityMockMvcRequestPostProcessors.user(learnerPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // 403 Forbidden without CSRF
        mockMvc.perform(
                        post("/api/v1/admin/content/grammar/topics")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // 400 Bad Request if unknown snapshot fields
        Map<String, Object> unknownFieldData = Map.of("name", "Nouns", "unknown_field", "value");
        CreateGrammarDraftRequest unknownFieldRequest =
                new CreateGrammarDraftRequest(
                        unknownFieldData, List.of(defaultSource.getId()), "Draft");
        mockMvc.perform(
                        post("/api/v1/admin/content/grammar/topics")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(unknownFieldRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 400 Bad Request if nested envelope data.data
        Map<String, Object> nestedEnvelopeData = Map.of("data", Map.of("name", "Nouns"));
        CreateGrammarDraftRequest nestedRequest =
                new CreateGrammarDraftRequest(
                        nestedEnvelopeData, List.of(defaultSource.getId()), "Draft");
        mockMvc.perform(
                        post("/api/v1/admin/content/grammar/topics")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(nestedRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // =========================================================================
    // GC1 / G2: Draft sai kiểu (isTextual) và integer overflow (canConvertToInt)
    // =========================================================================

    @Test
    @DisplayName("GC1 / G2: Chặn kiểu không phải chuỗi và tràn số nguyên ở Topic draft")
    void grammarTopicDraftInvalidTypesAndOverflow() throws Exception {
        int itemsBefore = itemRepository.findAll().size();

        // 1. name is an object: {"nested": 123}
        mockMvc.perform(
                        post("/api/v1/admin/content/grammar/topics")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateGrammarDraftRequest(
                                                        Map.of("name", Map.of("nested", 123)),
                                                        List.of(defaultSource.getId()),
                                                        "Bad name"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 2. name is a number: 123
        mockMvc.perform(
                        post("/api/v1/admin/content/grammar/topics")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateGrammarDraftRequest(
                                                        Map.of("name", 123),
                                                        List.of(defaultSource.getId()),
                                                        "Number name"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 3. slug is a number: 123
        mockMvc.perform(
                        post("/api/v1/admin/content/grammar/topics")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateGrammarDraftRequest(
                                                        Map.of("slug", 123),
                                                        List.of(defaultSource.getId()),
                                                        "Number slug"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 4. description is an array
        mockMvc.perform(
                        post("/api/v1/admin/content/grammar/topics")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateGrammarDraftRequest(
                                                        Map.of(
                                                                "description",
                                                                List.of("line 1", "line 2")),
                                                        List.of(defaultSource.getId()),
                                                        "Array description"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 5. difficulty is a number: 999
        mockMvc.perform(
                        post("/api/v1/admin/content/grammar/topics")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateGrammarDraftRequest(
                                                        Map.of("difficulty", 999),
                                                        List.of(defaultSource.getId()),
                                                        "Number difficulty"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 6. sort_order integer overflow: 4294967297L (2^32 + 1)
        mockMvc.perform(
                        post("/api/v1/admin/content/grammar/topics")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateGrammarDraftRequest(
                                                        Map.of(
                                                                "name",
                                                                "Valid",
                                                                "sort_order",
                                                                4294967297L),
                                                        List.of(defaultSource.getId()),
                                                        "Overflow sort_order"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 7. Generic PUT: Invalid typed fields & sort_order overflow on existing Topic draft
        CreateGrammarDraftRequest validTopicReq =
                new CreateGrammarDraftRequest(
                        Map.of("name", "Valid Topic", "difficulty", "BASIC"),
                        List.of(defaultSource.getId()),
                        "Valid Topic");
        String topRes =
                mockMvc.perform(
                                post("/api/v1/admin/content/grammar/topics")
                                        .with(
                                                SecurityMockMvcRequestPostProcessors.user(
                                                        adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(validTopicReq)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID topicRevId = UUID.fromString((String) toMap(topRes).get("latestRevisionId"));

        // PUT with name as object
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/" + topicRevId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        0L,
                                                        Map.of("name", Map.of("title", 123)),
                                                        List.of(defaultSource.getId()),
                                                        "Bad name obj"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // PUT with slug as number
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/" + topicRevId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        0L,
                                                        Map.of("slug", 99999),
                                                        List.of(defaultSource.getId()),
                                                        "Bad slug num"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // PUT with sort_order overflow
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/" + topicRevId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        0L,
                                                        Map.of("sort_order", 4294967297L),
                                                        List.of(defaultSource.getId()),
                                                        "Overflow sort_order"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Verify only 1 item created (the valid one) and no audit log mutated for failed requests
        assertThat(itemRepository.findAll().size()).isEqualTo(itemsBefore + 1);
    }

    @Test
    @DisplayName("GC1 / G2: Chặn kiểu không phải chuỗi và tràn số nguyên ở Lesson draft và blocks")
    void grammarLessonDraftInvalidTypesAndOverflow() throws Exception {
        int itemsBefore = itemRepository.findAll().size();

        // 1. title is a number
        mockMvc.perform(
                        post("/api/v1/admin/content/grammar/lessons")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateGrammarDraftRequest(
                                                        Map.of("title", 12345),
                                                        List.of(defaultSource.getId()),
                                                        "Number title"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 2. position integer overflow: 4294967297L
        mockMvc.perform(
                        post("/api/v1/admin/content/grammar/lessons")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateGrammarDraftRequest(
                                                        Map.of(
                                                                "title",
                                                                "Lesson",
                                                                "position",
                                                                4294967297L),
                                                        List.of(defaultSource.getId()),
                                                        "Overflow position"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 3. body_schema_version integer overflow: 4294967297L
        mockMvc.perform(
                        post("/api/v1/admin/content/grammar/lessons")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateGrammarDraftRequest(
                                                        Map.of(
                                                                "title",
                                                                "Lesson",
                                                                "body_schema_version",
                                                                4294967297L),
                                                        List.of(defaultSource.getId()),
                                                        "Overflow body_schema_version"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 4. estimated_minutes integer overflow: 4294967297L
        mockMvc.perform(
                        post("/api/v1/admin/content/grammar/lessons")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateGrammarDraftRequest(
                                                        Map.of(
                                                                "title",
                                                                "Lesson",
                                                                "estimated_minutes",
                                                                4294967297L),
                                                        List.of(defaultSource.getId()),
                                                        "Overflow estimated_minutes"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(itemRepository.findAll().size()).isEqualTo(itemsBefore);

        // 5. Generic PUT: Lesson block text is an object or array instead of string
        CreateGrammarDraftRequest validReq =
                new CreateGrammarDraftRequest(
                        Map.of("title", "Valid Lesson", "position", 1),
                        List.of(defaultSource.getId()),
                        "Valid");
        String res =
                mockMvc.perform(
                                post("/api/v1/admin/content/grammar/lessons")
                                        .with(
                                                SecurityMockMvcRequestPostProcessors.user(
                                                        adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(validReq)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID revId = UUID.fromString((String) toMap(res).get("latestRevisionId"));

        // Heading block text as object
        Map<String, Object> badHeading =
                Map.of("type", "heading", "text", Map.of("title", "Nested"));
        Map<String, Object> badBody1 = Map.of("blocks", List.of(badHeading));
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/" + revId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        0L,
                                                        Map.of(
                                                                "title",
                                                                "Valid Lesson",
                                                                "body",
                                                                badBody1),
                                                        List.of(defaultSource.getId()),
                                                        "Bad heading"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Paragraph block text as array
        Map<String, Object> badParagraph =
                Map.of("type", "paragraph", "text", List.of("Line 1", "Line 2"));
        Map<String, Object> badBody2 = Map.of("blocks", List.of(badParagraph));
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/" + revId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        0L,
                                                        Map.of(
                                                                "title",
                                                                "Valid Lesson",
                                                                "body",
                                                                badBody2),
                                                        List.of(defaultSource.getId()),
                                                        "Bad paragraph"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Table cell as integer
        Map<String, Object> badTable = Map.of("type", "table", "rows", List.of(List.of(12345)));
        Map<String, Object> badBody3 = Map.of("blocks", List.of(badTable));
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/" + revId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        0L,
                                                        Map.of(
                                                                "title",
                                                                "Valid Lesson",
                                                                "body",
                                                                badBody3),
                                                        List.of(defaultSource.getId()),
                                                        "Bad table"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Note block text as object
        Map<String, Object> badNote = Map.of("type", "note", "text", Map.of("key", "val"));
        Map<String, Object> badBody4 = Map.of("blocks", List.of(badNote));
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/" + revId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        0L,
                                                        Map.of(
                                                                "title",
                                                                "Valid Lesson",
                                                                "body",
                                                                badBody4),
                                                        List.of(defaultSource.getId()),
                                                        "Bad note"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Example block with example_en as number
        Map<String, Object> badExample = Map.of("type", "example", "example_en", 12345);
        Map<String, Object> badBody5 = Map.of("blocks", List.of(badExample));
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/" + revId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        0L,
                                                        Map.of(
                                                                "title",
                                                                "Valid Lesson",
                                                                "body",
                                                                badBody5),
                                                        List.of(defaultSource.getId()),
                                                        "Bad example"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Generic PUT with position overflow
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/" + revId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        0L,
                                                        Map.of(
                                                                "title",
                                                                "Valid Lesson",
                                                                "position",
                                                                4294967297L),
                                                        List.of(defaultSource.getId()),
                                                        "Overflow position"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Generic PUT with estimated_minutes overflow
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/" + revId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        0L,
                                                        Map.of(
                                                                "title",
                                                                "Valid Lesson",
                                                                "estimated_minutes",
                                                                4294967297L),
                                                        List.of(defaultSource.getId()),
                                                        "Overflow estimated_minutes"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Generic PUT with duplicate sourceIds and null sourceId element -> successfully
        // de-duplicated
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/" + revId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        0L,
                                                        Map.of("title", "Valid Lesson Updated"),
                                                        Arrays.asList(
                                                                defaultSource.getId(),
                                                                defaultSource.getId(),
                                                                null),
                                                        "De-duplicated sources"))))
                .andExpect(status().isOk());
        assertThat(sourceLinkRepository.findByIdRevisionId(revId)).hasSize(1);
    }

    // =========================================================================
    // G2 / AC04, AC16, AC21: Generic endpoints, CAS, IN_REVIEW guard, Reset DRAFT
    // =========================================================================

    @Test
    @DisplayName(
            "G2: Generic endpoints - GET detail, CAS stale 409, reset DRAFT on REJECTED edit, Lesson body bounds")
    void genericEditingEndpointsAndGuards() throws Exception {
        // 1. Create a lesson draft
        Map<String, Object> lessonData = new HashMap<>();
        lessonData.put("title", "Lesson 1");
        lessonData.put("position", 1);

        CreateGrammarDraftRequest createRequest =
                new CreateGrammarDraftRequest(
                        lessonData, List.of(defaultSource.getId()), "Initial");

        String createJson =
                mockMvc.perform(
                                post("/api/v1/admin/content/grammar/lessons")
                                        .with(
                                                SecurityMockMvcRequestPostProcessors.user(
                                                        adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(createRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        Map<String, Object> created = toMap(createJson);
        UUID itemId = UUID.fromString((String) created.get("id"));
        UUID revisionId = UUID.fromString((String) created.get("latestRevisionId"));

        // 2. GET detail via generic /items/{id}
        mockMvc.perform(
                        get("/api/v1/admin/content/items/" + itemId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(itemId.toString()))
                .andExpect(jsonPath("$.contentType").value("GRAMMAR_LESSON"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.title").value("Lesson 1"));

        // 3. Update draft successfully with valid body
        Map<String, Object> headingBlock = Map.of("type", "heading", "text", "Overview");
        Map<String, Object> paragraphBlock =
                Map.of("type", "paragraph", "text", "Explanation of tense");
        Map<String, Object> body = Map.of("blocks", List.of(headingBlock, paragraphBlock));

        Map<String, Object> updatedData = new HashMap<>(lessonData);
        updatedData.put("title", "Updated Lesson 1");
        updatedData.put("body", body);

        UpdateRevisionDraftRequest updateReq =
                new UpdateRevisionDraftRequest(
                        0L, updatedData, List.of(defaultSource.getId()), "Updated body");

        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/" + revisionId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // 4. CAS Conflict (stale expectedVersion = 0 when actual is 1)
        UpdateRevisionDraftRequest staleReq =
                new UpdateRevisionDraftRequest(
                        0L, updatedData, List.of(defaultSource.getId()), "Stale update");
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/" + revisionId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(staleReq)))
                .andExpect(status().isConflict());

        // 5. Submit for review -> IN_REVIEW
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revisionId + "/submit")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_REVIEW"));

        // 6. Attempt to edit IN_REVIEW revision -> 409 Conflict
        UpdateRevisionDraftRequest inReviewEditReq =
                new UpdateRevisionDraftRequest(
                        1L, updatedData, List.of(defaultSource.getId()), "Edit in review");
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/" + revisionId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(inReviewEditReq)))
                .andExpect(status().isConflict());

        // 7. Reject revision -> REJECTED
        ReviewDecisionRequest rejectReq = new ReviewDecisionRequest("Needs clearer examples");
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revisionId + "/reject")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(rejectReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // 8. Editing REJECTED revision resets status to DRAFT and clears review metadata
        ContentRevision revBeforeFix = revisionRepository.findById(revisionId).orElseThrow();
        UpdateRevisionDraftRequest fixReq =
                new UpdateRevisionDraftRequest(
                        revBeforeFix.getVersion(),
                        updatedData,
                        List.of(defaultSource.getId()),
                        "Fixed rejection");
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/" + revisionId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(fixReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        ContentRevision revInDb = revisionRepository.findById(revisionId).orElseThrow();
        assertThat(revInDb.getStatus()).isEqualTo("DRAFT");
        assertThat(revInDb.getReviewedBy()).isNull();
        assertThat(revInDb.getReviewedAt()).isNull();
        assertThat(revInDb.getReviewNote()).isNull();

        // 9. Lesson body HTML check -> 400 Bad Request
        Map<String, Object> htmlBlock =
                Map.of("type", "paragraph", "text", "Hello <script>alert(1)</script>");
        Map<String, Object> htmlBody = Map.of("blocks", List.of(htmlBlock));
        Map<String, Object> htmlData = new HashMap<>(lessonData);
        htmlData.put("body", htmlBody);

        UpdateRevisionDraftRequest htmlReq =
                new UpdateRevisionDraftRequest(
                        revInDb.getVersion(),
                        htmlData,
                        List.of(defaultSource.getId()),
                        "HTML body");
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/" + revisionId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(htmlReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // =========================================================================
    // GC3: G2 Approved-edit reset & Published lesson clone keeps dependencies/sources v1
    // =========================================================================

    @Test
    @DisplayName(
            "GC3 / G2: Sửa revision APPROVED reset về DRAFT và clone Published lesson bảo toàn v1 dependencies và sources")
    void approvedEditResetAndPublishedCloneIntegrity() throws Exception {
        // --- 1. Publish Topic ---
        Map<String, Object> topicData =
                Map.of(
                        "name", "Conditionals",
                        "slug", "conditionals",
                        "difficulty", "INTERMEDIATE");
        String topicRes =
                mockMvc.perform(
                                post("/api/v1/admin/content/grammar/topics")
                                        .with(
                                                SecurityMockMvcRequestPostProcessors.user(
                                                        adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        new CreateGrammarDraftRequest(
                                                                topicData,
                                                                List.of(defaultSource.getId()),
                                                                "T1"))))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID topicId = UUID.fromString((String) toMap(topicRes).get("id"));
        UUID topicRevId = UUID.fromString((String) toMap(topicRes).get("latestRevisionId"));

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + topicRevId + "/submit")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + topicRevId + "/approve")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + topicRevId + "/publish")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        // --- 2. Create and Approve Lesson Revision ---
        Map<String, Object> lessonBody =
                Map.of(
                        "blocks",
                        List.of(Map.of("type", "paragraph", "text", "If clause explanation")));
        Map<String, Object> lessonData = new HashMap<>();
        lessonData.put("topic_id", topicId.toString());
        lessonData.put("pinned_topic_revision_id", topicRevId.toString());
        lessonData.put("title", "First Conditional");
        lessonData.put("slug", "first-conditional");
        lessonData.put("position", 1);
        lessonData.put("body_schema_version", 1);
        lessonData.put("estimated_minutes", 10);
        lessonData.put("body", lessonBody);

        String lessonRes =
                mockMvc.perform(
                                post("/api/v1/admin/content/grammar/lessons")
                                        .with(
                                                SecurityMockMvcRequestPostProcessors.user(
                                                        adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        new CreateGrammarDraftRequest(
                                                                lessonData,
                                                                List.of(defaultSource.getId()),
                                                                "L1"))))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID lessonId = UUID.fromString((String) toMap(lessonRes).get("id"));
        UUID lessonRev1Id = UUID.fromString((String) toMap(lessonRes).get("latestRevisionId"));

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + lessonRev1Id + "/submit")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + lessonRev1Id + "/approve")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        ContentRevision approvedRev = revisionRepository.findById(lessonRev1Id).orElseThrow();
        assertThat(approvedRev.getStatus()).isEqualTo("APPROVED");
        assertThat(approvedRev.getReviewedBy()).isNotNull();

        // 3. Edit APPROVED revision -> MUST reset to DRAFT and clear reviewer metadata
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/" + lessonRev1Id)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        approvedRev.getVersion(),
                                                        lessonData,
                                                        List.of(defaultSource.getId()),
                                                        "Edit approved"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        ContentRevision resetRev = revisionRepository.findById(lessonRev1Id).orElseThrow();
        assertThat(resetRev.getStatus()).isEqualTo("DRAFT");
        assertThat(resetRev.getReviewedBy()).isNull();
        assertThat(resetRev.getReviewedAt()).isNull();
        assertThat(resetRev.getReviewNote()).isNull();

        // --- 4. Re-submit, re-approve, and publish Lesson v1 ---
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + lessonRev1Id + "/submit")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + lessonRev1Id + "/approve")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + lessonRev1Id + "/publish")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        // Verify v1 source snapshot captured and dependency recorded
        var v1Links = sourceLinkRepository.findByIdRevisionId(lessonRev1Id);
        assertThat(v1Links).hasSize(1);
        assertThat(v1Links.get(0).getSourceSnapshot()).isNotNull();
        var v1Deps = dependencyRepository.findByIdParentRevisionId(lessonRev1Id);
        assertThat(v1Deps).hasSize(1);
        assertThat(v1Deps.get(0).getChildRevisionId()).isEqualTo(topicRevId);

        // --- 5. Clone published lesson to v2 ---
        String cloneRes =
                mockMvc.perform(
                                post("/api/v1/admin/content/items/" + lessonId + "/revisions")
                                        .with(
                                                SecurityMockMvcRequestPostProcessors.user(
                                                        adminPrincipal()))
                                        .with(csrf()))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("DRAFT"))
                        .andExpect(jsonPath("$.versionNo").value(2))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID lessonRev2Id = UUID.fromString((String) toMap(cloneRes).get("revisionId"));
        assertThat(lessonRev2Id).isNotEqualTo(lessonRev1Id);

        // Verify v1 dependencies, snapshot, and sources REMAIN UNCHANGED
        ContentRevision v1InDb = revisionRepository.findById(lessonRev1Id).orElseThrow();
        assertThat(v1InDb.getStatus()).isEqualTo("PUBLISHED");
        var v1DepsAfterClone = dependencyRepository.findByIdParentRevisionId(lessonRev1Id);
        assertThat(v1DepsAfterClone).hasSize(1);
        assertThat(v1DepsAfterClone.get(0).getChildRevisionId()).isEqualTo(topicRevId);
        var v1LinksAfterClone = sourceLinkRepository.findByIdRevisionId(lessonRev1Id);
        assertThat(v1LinksAfterClone.get(0).getSourceSnapshot())
                .isEqualTo(v1Links.get(0).getSourceSnapshot());

        // Attempt to create another revision while v2 is open -> 409 Conflict
        mockMvc.perform(
                        post("/api/v1/admin/content/items/" + lessonId + "/revisions")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isConflict());

        // --- 6. Verify Learner DTO does not leak admin internal data ---
        mockMvc.perform(
                        get("/api/v1/grammar/lessons/" + lessonId)
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                learnerPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("First Conditional"))
                .andExpect(jsonPath("$.snapshot").doesNotExist())
                .andExpect(jsonPath("$.latestRevisionId").doesNotExist())
                .andExpect(jsonPath("$.reviewedBy").doesNotExist())
                .andExpect(jsonPath("$.sourceSnapshot").doesNotExist())
                .andExpect(jsonPath("$.sources").doesNotExist());
    }

    // =========================================================================
    // GC3 / G3: Preview / Validate zero mutations and DB assertions
    // =========================================================================

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName(
            "GC3 / G3: Preview validation trên Grammar topic/lesson là read-only, 0-mutation trên toàn bộ DB")
    void previewValidationZeroMutations() throws Exception {
        UUID[] ids =
                transactionTemplate.execute(
                        status -> {
                            try {
                                // 1. Create Topic and publish
                                Map<String, Object> topicData =
                                        Map.of(
                                                "name", "Adverbs",
                                                "slug", "adverbs",
                                                "difficulty", "BASIC");
                                String topicRes =
                                        mockMvc.perform(
                                                        post("/api/v1/admin/content/grammar/topics")
                                                                .with(
                                                                        SecurityMockMvcRequestPostProcessors
                                                                                .user(
                                                                                        adminPrincipal()))
                                                                .with(csrf())
                                                                .contentType(
                                                                        MediaType.APPLICATION_JSON)
                                                                .content(
                                                                        objectMapper
                                                                                .writeValueAsString(
                                                                                        new CreateGrammarDraftRequest(
                                                                                                topicData,
                                                                                                List
                                                                                                        .of(
                                                                                                                defaultSource
                                                                                                                        .getId()),
                                                                                                "T"))))
                                                .andExpect(status().isCreated())
                                                .andReturn()
                                                .getResponse()
                                                .getContentAsString();
                                UUID topId = UUID.fromString((String) toMap(topicRes).get("id"));
                                UUID topRevId =
                                        UUID.fromString(
                                                (String) toMap(topicRes).get("latestRevisionId"));

                                mockMvc.perform(
                                                post("/api/v1/admin/content/revisions/"
                                                                + topRevId
                                                                + "/submit")
                                                        .with(
                                                                SecurityMockMvcRequestPostProcessors
                                                                        .user(adminPrincipal()))
                                                        .with(csrf()))
                                        .andExpect(status().isOk());
                                mockMvc.perform(
                                                post("/api/v1/admin/content/revisions/"
                                                                + topRevId
                                                                + "/approve")
                                                        .with(
                                                                SecurityMockMvcRequestPostProcessors
                                                                        .user(adminPrincipal()))
                                                        .with(csrf()))
                                        .andExpect(status().isOk());
                                mockMvc.perform(
                                                post("/api/v1/admin/content/revisions/"
                                                                + topRevId
                                                                + "/publish")
                                                        .with(
                                                                SecurityMockMvcRequestPostProcessors
                                                                        .user(adminPrincipal()))
                                                        .with(csrf()))
                                        .andExpect(status().isOk());

                                // 2. Create valid Lesson draft
                                Map<String, Object> lessonBody =
                                        Map.of(
                                                "blocks",
                                                List.of(
                                                        Map.of(
                                                                "type",
                                                                "paragraph",
                                                                "text",
                                                                "Adverb usage")));
                                Map<String, Object> validLessonData = new HashMap<>();
                                validLessonData.put("topic_id", topId.toString());
                                validLessonData.put(
                                        "pinned_topic_revision_id", topRevId.toString());
                                validLessonData.put("title", "Adverbs of Frequency");
                                validLessonData.put("slug", "adverbs-of-frequency");
                                validLessonData.put("position", 1);
                                validLessonData.put("body_schema_version", 1);
                                validLessonData.put("estimated_minutes", 10);
                                validLessonData.put("body", lessonBody);

                                String validRes =
                                        mockMvc.perform(
                                                        post("/api/v1/admin/content/grammar/lessons")
                                                                .with(
                                                                        SecurityMockMvcRequestPostProcessors
                                                                                .user(
                                                                                        adminPrincipal()))
                                                                .with(csrf())
                                                                .contentType(
                                                                        MediaType.APPLICATION_JSON)
                                                                .content(
                                                                        objectMapper
                                                                                .writeValueAsString(
                                                                                        new CreateGrammarDraftRequest(
                                                                                                validLessonData,
                                                                                                List
                                                                                                        .of(
                                                                                                                defaultSource
                                                                                                                        .getId()),
                                                                                                "Valid L"))))
                                                .andExpect(status().isCreated())
                                                .andReturn()
                                                .getResponse()
                                                .getContentAsString();
                                UUID valLesId = UUID.fromString((String) toMap(validRes).get("id"));
                                UUID valLesRevId =
                                        UUID.fromString(
                                                (String) toMap(validRes).get("latestRevisionId"));

                                // 3. Create invalid Lesson draft (pinning non-existent topic
                                // revision)
                                Map<String, Object> invalidLessonData =
                                        new HashMap<>(validLessonData);
                                invalidLessonData.put("title", "Invalid Lesson");
                                invalidLessonData.put(
                                        "pinned_topic_revision_id", UUID.randomUUID().toString());

                                String invalidRes =
                                        mockMvc.perform(
                                                        post("/api/v1/admin/content/grammar/lessons")
                                                                .with(
                                                                        SecurityMockMvcRequestPostProcessors
                                                                                .user(
                                                                                        adminPrincipal()))
                                                                .with(csrf())
                                                                .contentType(
                                                                        MediaType.APPLICATION_JSON)
                                                                .content(
                                                                        objectMapper
                                                                                .writeValueAsString(
                                                                                        new CreateGrammarDraftRequest(
                                                                                                invalidLessonData,
                                                                                                List
                                                                                                        .of(
                                                                                                                defaultSource
                                                                                                                        .getId()),
                                                                                                "Invalid L"))))
                                                .andExpect(status().isCreated())
                                                .andReturn()
                                                .getResponse()
                                                .getContentAsString();
                                UUID invLesId =
                                        UUID.fromString((String) toMap(invalidRes).get("id"));
                                UUID invLesRevId =
                                        UUID.fromString(
                                                (String) toMap(invalidRes).get("latestRevisionId"));

                                return new UUID[] {
                                    topId, topRevId, valLesId, valLesRevId, invLesId, invLesRevId
                                };
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });

        UUID topicId = ids[0];
        UUID topicRevId = ids[1];
        UUID validLessonId = ids[2];
        UUID validLessonRevId = ids[3];
        UUID invalidLessonId = ids[4];
        UUID invalidLessonRevId = ids[5];

        try {
            // Record DB state BEFORE validate (via real DB queries outside transaction)
            Integer auditCountBefore =
                    jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);
            Integer depCountBefore =
                    jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM revision_dependencies", Integer.class);
            Integer lessonProjCountBefore =
                    jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM grammar_lessons", Integer.class);

            // Execute Validate on VALID revision
            mockMvc.perform(
                            post("/api/v1/admin/content/revisions/"
                                            + validLessonRevId
                                            + "/validate")
                                    .with(
                                            SecurityMockMvcRequestPostProcessors.user(
                                                    adminPrincipal()))
                                    .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(true))
                    .andExpect(jsonPath("$.errors").isEmpty());

            // Execute Validate on INVALID revision
            mockMvc.perform(
                            post("/api/v1/admin/content/revisions/"
                                            + invalidLessonRevId
                                            + "/validate")
                                    .with(
                                            SecurityMockMvcRequestPostProcessors.user(
                                                    adminPrincipal()))
                                    .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(false))
                    .andExpect(jsonPath("$.errors").isNotEmpty());

            // Assert DB state AFTER validate: EXACTLY ZERO MUTATIONS directly against PostgreSQL
            Integer auditCountAfter =
                    jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);
            Integer depCountAfter =
                    jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM revision_dependencies", Integer.class);
            Integer lessonProjCountAfter =
                    jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM grammar_lessons", Integer.class);

            assertThat(auditCountAfter).isEqualTo(auditCountBefore);
            assertThat(depCountAfter).isEqualTo(depCountBefore);
            assertThat(lessonProjCountAfter).isEqualTo(lessonProjCountBefore);

            // Specific row checks
            ContentItem validItem = itemRepository.findById(validLessonId).orElseThrow();
            assertThat(validItem.getPublishedRevisionId()).isNull();
            ContentItem invalidItem = itemRepository.findById(invalidLessonId).orElseThrow();
            assertThat(invalidItem.getPublishedRevisionId()).isNull();

            assertThat(
                            sourceLinkRepository
                                    .findByIdRevisionId(validLessonRevId)
                                    .get(0)
                                    .getSourceSnapshot())
                    .isNull();
            assertThat(
                            sourceLinkRepository
                                    .findByIdRevisionId(invalidLessonRevId)
                                    .get(0)
                                    .getSourceSnapshot())
                    .isNull();
        } finally {
            transactionTemplate.execute(
                    status -> {
                        jdbcTemplate.update(
                                "DELETE FROM grammar_lessons WHERE id IN (?, ?)",
                                validLessonId,
                                invalidLessonId);
                        jdbcTemplate.update("DELETE FROM grammar_topics WHERE id = ?", topicId);
                        jdbcTemplate.update(
                                "UPDATE content_items SET is_archived = true, archived_at = CURRENT_TIMESTAMP WHERE id IN (?, ?, ?)",
                                topicId,
                                validLessonId,
                                invalidLessonId);
                        return null;
                    });
        }
    }

    // =========================================================================
    // G3 / AC04, AC16, AC18–20: Happy Path Publish, Read Projection, Failure Rollback
    // =========================================================================

    @Test
    @DisplayName(
            "G3: Full publication topic + pinned lesson, learner view and publish failure rollback")
    void fullPublicationWorkflowAndRollback() throws Exception {
        // --- Step 1: Create Topic Draft and Publish ---
        Map<String, Object> topicData = new HashMap<>();
        topicData.put("name", "Articles & Determiners");
        topicData.put("slug", "articles-and-determiners");
        topicData.put("description", "A, An, The usage");
        topicData.put("difficulty", "BASIC");
        topicData.put("sort_order", 10);

        CreateGrammarDraftRequest topicDraftReq =
                new CreateGrammarDraftRequest(
                        topicData, List.of(defaultSource.getId()), "Publish topic");

        String topicRes =
                mockMvc.perform(
                                post("/api/v1/admin/content/grammar/topics")
                                        .with(
                                                SecurityMockMvcRequestPostProcessors.user(
                                                        adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(topicDraftReq)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        UUID topicItemId = UUID.fromString((String) toMap(topicRes).get("id"));
        UUID topicRevId = UUID.fromString((String) toMap(topicRes).get("latestRevisionId"));

        // Submit topic
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + topicRevId + "/submit")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        // Approve topic
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + topicRevId + "/approve")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        // Publish topic
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + topicRevId + "/publish")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        // Verify topic projected to read table grammar_topics
        var topicProjection = topicRepository.findById(topicItemId);
        assertThat(topicProjection).isPresent();
        assertThat(topicProjection.get().getName()).isEqualTo("Articles & Determiners");
        assertThat(topicProjection.get().getSlug()).isEqualTo("articles-and-determiners");
        assertThat(topicProjection.get().getDifficulty()).isEqualTo("BASIC");

        // Learner reads via GET /api/v1/grammar/topics
        mockMvc.perform(
                        get("/api/v1/grammar/topics")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                learnerPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.slug == 'articles-and-determiners')]").exists());

        // --- Step 2: Create Lesson Draft pinning published topic, Publish and Learner reads ---
        Map<String, Object> headingBlock =
                Map.of("type", "heading", "text", "The Indefinite Article");
        Map<String, Object> paraBlock =
                Map.of("type", "paragraph", "text", "We use 'a' before consonant sounds.");
        Map<String, Object> exBlock =
                Map.of("type", "example", "example_en", "A cat", "example_vi", "Một con mèo");
        Map<String, Object> lessonBody =
                Map.of("blocks", List.of(headingBlock, paraBlock, exBlock));

        Map<String, Object> lessonData = new HashMap<>();
        lessonData.put("topic_id", topicItemId.toString());
        lessonData.put("pinned_topic_revision_id", topicRevId.toString());
        lessonData.put("title", "A and An");
        lessonData.put("slug", "a-and-an");
        lessonData.put("position", 1);
        lessonData.put("body_schema_version", 1);
        lessonData.put("estimated_minutes", 10);
        lessonData.put("body", lessonBody);

        CreateGrammarDraftRequest lessonDraftReq =
                new CreateGrammarDraftRequest(
                        lessonData, List.of(defaultSource.getId()), "Publish lesson");

        String lessonRes =
                mockMvc.perform(
                                post("/api/v1/admin/content/grammar/lessons")
                                        .with(
                                                SecurityMockMvcRequestPostProcessors.user(
                                                        adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(lessonDraftReq)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        UUID lessonItemId = UUID.fromString((String) toMap(lessonRes).get("id"));
        UUID lessonRevId = UUID.fromString((String) toMap(lessonRes).get("latestRevisionId"));

        // Validate preview is read-only
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + lessonRevId + "/validate")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        // Submit & Approve Lesson
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + lessonRevId + "/submit")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + lessonRevId + "/approve")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        // Publish Lesson
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + lessonRevId + "/publish")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        // Verify projected to read table grammar_lessons
        var lessonProjection = lessonRepository.findById(lessonItemId);
        assertThat(lessonProjection).isPresent();
        assertThat(lessonProjection.get().getTitle()).isEqualTo("A and An");
        assertThat(lessonProjection.get().getTopicId()).isEqualTo(topicItemId);

        // Verify revision_dependencies has topic pin
        var deps = dependencyRepository.findByIdParentRevisionId(lessonRevId);
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).getId().getRelationKey()).isEqualTo("topic:" + topicItemId);
        assertThat(deps.get(0).getChildRevisionId()).isEqualTo(topicRevId);

        // Learner reads via GET /api/v1/grammar/topics/{topicId}/lessons
        mockMvc.perform(
                        get("/api/v1/grammar/topics/" + topicItemId + "/lessons")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                learnerPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'A and An')]").exists());

        // Learner reads detail via GET /api/v1/grammar/lessons/{lessonId}
        mockMvc.perform(
                        get("/api/v1/grammar/lessons/" + lessonItemId)
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                learnerPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("A and An"))
                .andExpect(jsonPath("$.topicId").value(topicItemId.toString()));

        // --- Step 3: Publish failure scenario: Lesson referencing media fails publication ---
        UUID fakeMediaId = UUID.randomUUID();
        Map<String, Object> badLessonData = new HashMap<>(lessonData);
        badLessonData.put("title", "Lesson with Media");
        badLessonData.put("slug", "lesson-with-media");

        CreateGrammarDraftRequest badLessonReq =
                new CreateGrammarDraftRequest(
                        badLessonData, List.of(defaultSource.getId()), "Bad lesson");
        String badRes =
                mockMvc.perform(
                                post("/api/v1/admin/content/grammar/lessons")
                                        .with(
                                                SecurityMockMvcRequestPostProcessors.user(
                                                        adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(badLessonReq)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        UUID badLessonId = UUID.fromString((String) toMap(badRes).get("id"));
        UUID badRevId = UUID.fromString((String) toMap(badRes).get("latestRevisionId"));

        // Insert a media ref
        jdbcTemplate.update(
                "INSERT INTO media_assets (id, media_type, purpose, original_filename, uploaded_by, status, created_at, updated_at) "
                        + "VALUES (?, 'IMAGE', 'CONTENT', 'diagram.png', ?, 'PENDING', NOW(), NOW())",
                fakeMediaId,
                contentAdminUser.getId());
        jdbcTemplate.update(
                "INSERT INTO revision_media_refs (revision_id, media_id, usage_role) VALUES (?, ?, 'STIMULUS')",
                badRevId,
                fakeMediaId);

        // Submit & Approve
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + badRevId + "/submit")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + badRevId + "/approve")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        // Publish must fail with 400 Bad Request
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + badRevId + "/publish")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Verify rollback: item is NOT published
        ContentItem badItem = itemRepository.findById(badLessonId).orElseThrow();
        assertThat(badItem.getPublishedRevisionId()).isNull();
        assertThat(lessonRepository.findById(badLessonId)).isEmpty();
    }

    // =========================================================================
    // G4 / AC04, AC20–21: Pin Immutability and Topic Archive Guard at Publish
    // =========================================================================

    @Test
    @DisplayName(
            "G4: Topic v2 xuất bản không làm đổi pin của Lesson v1; Topic bị archive chặn Lesson publish")
    void topicPinImmutabilityAndArchiveGuard() throws Exception {
        // 1. Create and publish Topic v1
        Map<String, Object> topicData = new HashMap<>();
        topicData.put("name", "Pronouns");
        topicData.put("slug", "pronouns");
        topicData.put("difficulty", "BASIC");
        topicData.put("sort_order", 1);

        String topicRes =
                mockMvc.perform(
                                post("/api/v1/admin/content/grammar/topics")
                                        .with(
                                                SecurityMockMvcRequestPostProcessors.user(
                                                        adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        new CreateGrammarDraftRequest(
                                                                topicData,
                                                                List.of(defaultSource.getId()),
                                                                "T1"))))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        UUID topicId = UUID.fromString((String) toMap(topicRes).get("id"));
        UUID topicRev1 = UUID.fromString((String) toMap(topicRes).get("latestRevisionId"));

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + topicRev1 + "/submit")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + topicRev1 + "/approve")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + topicRev1 + "/publish")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        // 2. Create and publish Lesson v1 pinning Topic v1
        Map<String, Object> lessonBody =
                Map.of("blocks", List.of(Map.of("type", "paragraph", "text", "Personal pronouns")));
        Map<String, Object> lessonData = new HashMap<>();
        lessonData.put("topic_id", topicId.toString());
        lessonData.put("pinned_topic_revision_id", topicRev1.toString());
        lessonData.put("title", "Personal Pronouns");
        lessonData.put("slug", "personal-pronouns");
        lessonData.put("position", 1);
        lessonData.put("body", lessonBody);

        String lessonRes =
                mockMvc.perform(
                                post("/api/v1/admin/content/grammar/lessons")
                                        .with(
                                                SecurityMockMvcRequestPostProcessors.user(
                                                        adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        new CreateGrammarDraftRequest(
                                                                lessonData,
                                                                List.of(defaultSource.getId()),
                                                                "L1"))))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        UUID lessonId = UUID.fromString((String) toMap(lessonRes).get("id"));
        UUID lessonRev1 = UUID.fromString((String) toMap(lessonRes).get("latestRevisionId"));

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + lessonRev1 + "/submit")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + lessonRev1 + "/approve")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + lessonRev1 + "/publish")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        // 3. Clone Topic to v2 and publish Topic v2
        String cloneRes =
                mockMvc.perform(
                                post("/api/v1/admin/content/items/" + topicId + "/revisions")
                                        .with(
                                                SecurityMockMvcRequestPostProcessors.user(
                                                        adminPrincipal()))
                                        .with(csrf()))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        UUID topicRev2 = UUID.fromString((String) toMap(cloneRes).get("revisionId"));
        assertThat(topicRev2).isNotEqualTo(topicRev1);

        // Edit Topic v2
        Map<String, Object> topicV2Data = new HashMap<>(topicData);
        topicV2Data.put("name", "Pronouns & Possessives");
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/" + topicRev2)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        0L,
                                                        topicV2Data,
                                                        List.of(defaultSource.getId()),
                                                        "V2 update"))))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + topicRev2 + "/submit")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + topicRev2 + "/approve")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + topicRev2 + "/publish")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        // 4. Invariant assertion: Lesson v1 pin MUST STILL POINT to Topic v1!
        var lessonDeps = dependencyRepository.findByIdParentRevisionId(lessonRev1);
        assertThat(lessonDeps).hasSize(1);
        assertThat(lessonDeps.get(0).getChildRevisionId()).isEqualTo(topicRev1);
        assertThat(lessonDeps.get(0).getChildRevisionId()).isNotEqualTo(topicRev2);

        // 5. Test Topic archive guard: create Lesson 2, approve it, but archive Topic before Lesson
        // 2 publish
        Map<String, Object> lesson2Data = new HashMap<>(lessonData);
        lesson2Data.put("title", "Possessive Pronouns");
        lesson2Data.put("slug", "possessive-pronouns");
        lesson2Data.put("pinned_topic_revision_id", topicRev2.toString());

        String l2Res =
                mockMvc.perform(
                                post("/api/v1/admin/content/grammar/lessons")
                                        .with(
                                                SecurityMockMvcRequestPostProcessors.user(
                                                        adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        new CreateGrammarDraftRequest(
                                                                lesson2Data,
                                                                List.of(defaultSource.getId()),
                                                                "L2"))))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        UUID l2RevId = UUID.fromString((String) toMap(l2Res).get("latestRevisionId"));
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + l2RevId + "/submit")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + l2RevId + "/approve")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        // Archive the topic
        mockMvc.perform(
                        post("/api/v1/admin/content/items/" + topicId + "/archive")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Archived topic\"}"))
                .andExpect(status().isOk());

        // Attempt to publish Lesson 2 -> MUST BE BLOCKED (400 Bad Request)
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + l2RevId + "/publish")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // =========================================================================
    // GC3 / G3–G4: Concurrency test with 2 transactions using CountDownLatch & timeouts
    // =========================================================================

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName(
            "GC3 / G4: Concurrency 2 transactions - serializable order giữa Topic archive và Lesson publish")
    void concurrentArchiveAndPublishSerializableOutcome() throws Exception {
        // Setup via TransactionTemplate so data is committed to PostgreSQL for independent threads
        UUID[] ids =
                transactionTemplate.execute(
                        status -> {
                            try {
                                // 1. Create and publish Topic
                                Map<String, Object> topicData =
                                        Map.of(
                                                "name", "Prepositions",
                                                "slug", "prepositions",
                                                "difficulty", "BASIC");
                                MvcResult topicRes =
                                        mockMvc.perform(
                                                        post("/api/v1/admin/content/grammar/topics")
                                                                .with(
                                                                        SecurityMockMvcRequestPostProcessors
                                                                                .user(
                                                                                        adminPrincipal()))
                                                                .with(csrf())
                                                                .contentType(
                                                                        MediaType.APPLICATION_JSON)
                                                                .content(
                                                                        objectMapper
                                                                                .writeValueAsString(
                                                                                        new CreateGrammarDraftRequest(
                                                                                                topicData,
                                                                                                List
                                                                                                        .of(
                                                                                                                defaultSource
                                                                                                                        .getId()),
                                                                                                "T"))))
                                                .andExpect(status().isCreated())
                                                .andReturn();
                                UUID topId =
                                        UUID.fromString(
                                                (String)
                                                        toMap(
                                                                        topicRes.getResponse()
                                                                                .getContentAsString())
                                                                .get("id"));
                                UUID topRev =
                                        UUID.fromString(
                                                (String)
                                                        toMap(
                                                                        topicRes.getResponse()
                                                                                .getContentAsString())
                                                                .get("latestRevisionId"));

                                mockMvc.perform(
                                                post("/api/v1/admin/content/revisions/"
                                                                + topRev
                                                                + "/submit")
                                                        .with(
                                                                SecurityMockMvcRequestPostProcessors
                                                                        .user(adminPrincipal()))
                                                        .with(csrf()))
                                        .andExpect(status().isOk());
                                mockMvc.perform(
                                                post("/api/v1/admin/content/revisions/"
                                                                + topRev
                                                                + "/approve")
                                                        .with(
                                                                SecurityMockMvcRequestPostProcessors
                                                                        .user(adminPrincipal()))
                                                        .with(csrf()))
                                        .andExpect(status().isOk());
                                mockMvc.perform(
                                                post("/api/v1/admin/content/revisions/"
                                                                + topRev
                                                                + "/publish")
                                                        .with(
                                                                SecurityMockMvcRequestPostProcessors
                                                                        .user(adminPrincipal()))
                                                        .with(csrf()))
                                        .andExpect(status().isOk());

                                // 2. Create and approve Lesson pinning Topic
                                Map<String, Object> lessonBody =
                                        Map.of(
                                                "blocks",
                                                List.of(
                                                        Map.of(
                                                                "type",
                                                                "paragraph",
                                                                "text",
                                                                "In, on, at")));
                                Map<String, Object> lessonData = new HashMap<>();
                                lessonData.put("topic_id", topId.toString());
                                lessonData.put("pinned_topic_revision_id", topRev.toString());
                                lessonData.put("title", "Prepositions of Place");
                                lessonData.put("slug", "prepositions-of-place");
                                lessonData.put("position", 1);
                                lessonData.put("body_schema_version", 1);
                                lessonData.put("estimated_minutes", 10);
                                lessonData.put("body", lessonBody);

                                MvcResult lessonRes =
                                        mockMvc.perform(
                                                        post("/api/v1/admin/content/grammar/lessons")
                                                                .with(
                                                                        SecurityMockMvcRequestPostProcessors
                                                                                .user(
                                                                                        adminPrincipal()))
                                                                .with(csrf())
                                                                .contentType(
                                                                        MediaType.APPLICATION_JSON)
                                                                .content(
                                                                        objectMapper
                                                                                .writeValueAsString(
                                                                                        new CreateGrammarDraftRequest(
                                                                                                lessonData,
                                                                                                List
                                                                                                        .of(
                                                                                                                defaultSource
                                                                                                                        .getId()),
                                                                                                "L"))))
                                                .andExpect(status().isCreated())
                                                .andReturn();
                                UUID lesId =
                                        UUID.fromString(
                                                (String)
                                                        toMap(
                                                                        lessonRes
                                                                                .getResponse()
                                                                                .getContentAsString())
                                                                .get("id"));
                                UUID lesRev =
                                        UUID.fromString(
                                                (String)
                                                        toMap(
                                                                        lessonRes
                                                                                .getResponse()
                                                                                .getContentAsString())
                                                                .get("latestRevisionId"));

                                mockMvc.perform(
                                                post("/api/v1/admin/content/revisions/"
                                                                + lesRev
                                                                + "/submit")
                                                        .with(
                                                                SecurityMockMvcRequestPostProcessors
                                                                        .user(adminPrincipal()))
                                                        .with(csrf()))
                                        .andExpect(status().isOk());
                                mockMvc.perform(
                                                post("/api/v1/admin/content/revisions/"
                                                                + lesRev
                                                                + "/approve")
                                                        .with(
                                                                SecurityMockMvcRequestPostProcessors
                                                                        .user(adminPrincipal()))
                                                        .with(csrf()))
                                        .andExpect(status().isOk());

                                return new UUID[] {topId, topRev, lesId, lesRev};
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });

        UUID topicId = ids[0];
        UUID topicRevId = ids[1];
        UUID lessonId = ids[2];
        UUID lessonRevId = ids[3];

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Long> archivePidHolder = new AtomicReference<>();
        AtomicReference<Long> publishPidHolder = new AtomicReference<>();
        CountDownLatch archiveAcquiredLatch = new CountDownLatch(1);
        CountDownLatch archiveReleaseLatch = new CountDownLatch(1);
        CountDownLatch publishStartedLatch = new CountDownLatch(1);
        try {
            // Thread 1: Archive Transaction acquires exclusive FOR UPDATE lock on Topic and holds
            // transaction open
            Future<Void> archiveFuture =
                    executor.submit(
                            () -> {
                                transactionTemplate.execute(
                                        status -> {
                                            Long pid =
                                                    jdbcTemplate.queryForObject(
                                                            "SELECT pg_backend_pid()", Long.class);
                                            archivePidHolder.set(pid);
                                            authoringService.archiveItem(
                                                    topicId,
                                                    "Competing archive",
                                                    contentAdminUser.getId());
                                            archiveAcquiredLatch.countDown();
                                            try {
                                                assertThat(
                                                                archiveReleaseLatch.await(
                                                                        10, TimeUnit.SECONDS))
                                                        .isTrue();
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                                throw new RuntimeException(e);
                                            }
                                            return null;
                                        });
                                return null;
                            });

            // Ensure Thread 1 has acquired the lock before Thread 2 attempts publication
            assertThat(archiveAcquiredLatch.await(5, TimeUnit.SECONDS)).isTrue();
            Long archivePid = archivePidHolder.get();
            assertThat(archivePid).isNotNull();

            // Thread 2: Publish Transaction starts and attempts to publish lesson revision
            // (requires FOR SHARE of topic)
            Future<ContentRevisionResponse> publishFuture =
                    executor.submit(
                            () -> {
                                return transactionTemplate.execute(
                                        status -> {
                                            Long pid =
                                                    jdbcTemplate.queryForObject(
                                                            "SELECT pg_backend_pid()", Long.class);
                                            publishPidHolder.set(pid);
                                            publishStartedLatch.countDown();
                                            return authoringService.publish(
                                                    lessonRevId, secondContentAdminUser.getId());
                                        });
                            });

            assertThat(publishStartedLatch.await(5, TimeUnit.SECONDS)).isTrue();
            Long publishPid = publishPidHolder.get();
            assertThat(publishPid).isNotNull();

            // Verify Thread 2 (publisher) is actively blocked by Thread 1 (archive PID) in
            // PostgreSQL
            assertThat(awaitBlockedBy(publishPid, archivePid, 5)).isTrue();
            assertThat(publishFuture.isDone()).isFalse();

            // Commit Thread 1 (Archive Topic)
            archiveReleaseLatch.countDown();
            archiveFuture.get(5, TimeUnit.SECONDS);

            // Now Thread 2 unblocks, re-evaluates visible topic snapshot (is_archived = true), and
            // must fail with VALIDATION_ERROR
            ExecutionException ex =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            ExecutionException.class, () -> publishFuture.get(5, TimeUnit.SECONDS));
            Throwable cause = ex.getCause();
            assertThat(cause).isInstanceOf(BusinessException.class);
            BusinessException be = (BusinessException) cause;
            assertThat(be.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
            assertThat(be.getMessage())
                    .satisfiesAnyOf(
                            msg ->
                                    assertThat(msg)
                                            .contains(
                                                    "pinned_topic_revision_id must be a published revision of that topic."),
                            msg ->
                                    assertThat(msg)
                                            .contains(
                                                    "topic_id must reference a published topic."));

            // Database assertions: Lesson is NOT published, no projections or dependencies created
            ContentItem lessonItem = itemRepository.findById(lessonId).orElseThrow();
            assertThat(lessonItem.getPublishedRevisionId()).isNull();
            assertThat(lessonRepository.findById(lessonId)).isEmpty();
            assertThat(dependencyRepository.findByIdParentRevisionId(lessonRevId)).isEmpty();

            // Revision retains status APPROVED and source snapshot is NOT written (remains null)
            ContentRevision lessonRev = revisionRepository.findById(lessonRevId).orElseThrow();
            assertThat(lessonRev.getStatus()).isEqualTo("APPROVED");
            var sourceLinks = sourceLinkRepository.findByIdRevisionId(lessonRevId);
            assertThat(sourceLinks).isNotEmpty();
            for (var sl : sourceLinks) {
                assertThat(sl.getSourceSnapshot()).isNull();
            }

            ContentItem topicItem = itemRepository.findById(topicId).orElseThrow();
            assertThat(topicItem.isArchived()).isTrue();

            Integer pubAuditCount =
                    jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM audit_logs WHERE entity_id = ? AND action = 'CONTENT_PUBLISHED'",
                            Integer.class,
                            lessonId);
            assertThat(pubAuditCount).isEqualTo(0);
        } finally {
            archiveReleaseLatch.countDown();
            boolean terminated = false;
            try {
                executor.shutdown();
                terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
                if (!terminated) {
                    executor.shutdownNow();
                    terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                org.junit.jupiter.api.Assertions.fail(
                        "Executor interrupted while waiting for worker termination", e);
            }
            if (!terminated) {
                org.junit.jupiter.api.Assertions.fail(
                        "Executor failed to terminate workers within bounded timeout");
            }
            transactionTemplate.execute(
                    status -> {
                        jdbcTemplate.update("DELETE FROM grammar_lessons WHERE id = ?", lessonId);
                        jdbcTemplate.update("DELETE FROM grammar_topics WHERE id = ?", topicId);
                        jdbcTemplate.update(
                                "UPDATE content_items SET is_archived = true, archived_at = CURRENT_TIMESTAMP WHERE id IN (?, ?)",
                                topicId,
                                lessonId);
                        return null;
                    });
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName(
            "GC3 / G4: Concurrency 2 transactions - serializable order khi Lesson publish hoàn thành trước Topic archive")
    void concurrentPublishWinsBeforeArchiveSerializableOutcome() throws Exception {
        UUID[] ids =
                transactionTemplate.execute(
                        status -> {
                            try {
                                // 1. Create and publish Topic
                                Map<String, Object> topicData =
                                        Map.of(
                                                "name", "Conjunctions",
                                                "slug", "conjunctions",
                                                "difficulty", "BASIC");
                                MvcResult topicRes =
                                        mockMvc.perform(
                                                        post("/api/v1/admin/content/grammar/topics")
                                                                .with(
                                                                        SecurityMockMvcRequestPostProcessors
                                                                                .user(
                                                                                        adminPrincipal()))
                                                                .with(csrf())
                                                                .contentType(
                                                                        MediaType.APPLICATION_JSON)
                                                                .content(
                                                                        objectMapper
                                                                                .writeValueAsString(
                                                                                        new CreateGrammarDraftRequest(
                                                                                                topicData,
                                                                                                List
                                                                                                        .of(
                                                                                                                defaultSource
                                                                                                                        .getId()),
                                                                                                "T"))))
                                                .andExpect(status().isCreated())
                                                .andReturn();
                                UUID topId =
                                        UUID.fromString(
                                                (String)
                                                        toMap(
                                                                        topicRes.getResponse()
                                                                                .getContentAsString())
                                                                .get("id"));
                                UUID topRev =
                                        UUID.fromString(
                                                (String)
                                                        toMap(
                                                                        topicRes.getResponse()
                                                                                .getContentAsString())
                                                                .get("latestRevisionId"));

                                mockMvc.perform(
                                                post("/api/v1/admin/content/revisions/"
                                                                + topRev
                                                                + "/submit")
                                                        .with(
                                                                SecurityMockMvcRequestPostProcessors
                                                                        .user(adminPrincipal()))
                                                        .with(csrf()))
                                        .andExpect(status().isOk());
                                mockMvc.perform(
                                                post("/api/v1/admin/content/revisions/"
                                                                + topRev
                                                                + "/approve")
                                                        .with(
                                                                SecurityMockMvcRequestPostProcessors
                                                                        .user(adminPrincipal()))
                                                        .with(csrf()))
                                        .andExpect(status().isOk());
                                mockMvc.perform(
                                                post("/api/v1/admin/content/revisions/"
                                                                + topRev
                                                                + "/publish")
                                                        .with(
                                                                SecurityMockMvcRequestPostProcessors
                                                                        .user(adminPrincipal()))
                                                        .with(csrf()))
                                        .andExpect(status().isOk());

                                // 2. Create and approve Lesson pinning Topic
                                Map<String, Object> lessonBody =
                                        Map.of(
                                                "blocks",
                                                List.of(
                                                        Map.of(
                                                                "type",
                                                                "paragraph",
                                                                "text",
                                                                "And, but, or")));
                                Map<String, Object> lessonData = new HashMap<>();
                                lessonData.put("topic_id", topId.toString());
                                lessonData.put("pinned_topic_revision_id", topRev.toString());
                                lessonData.put("title", "Coordinating Conjunctions");
                                lessonData.put("slug", "coordinating-conjunctions");
                                lessonData.put("position", 1);
                                lessonData.put("body_schema_version", 1);
                                lessonData.put("estimated_minutes", 10);
                                lessonData.put("body", lessonBody);

                                MvcResult lessonRes =
                                        mockMvc.perform(
                                                        post("/api/v1/admin/content/grammar/lessons")
                                                                .with(
                                                                        SecurityMockMvcRequestPostProcessors
                                                                                .user(
                                                                                        adminPrincipal()))
                                                                .with(csrf())
                                                                .contentType(
                                                                        MediaType.APPLICATION_JSON)
                                                                .content(
                                                                        objectMapper
                                                                                .writeValueAsString(
                                                                                        new CreateGrammarDraftRequest(
                                                                                                lessonData,
                                                                                                List
                                                                                                        .of(
                                                                                                                defaultSource
                                                                                                                        .getId()),
                                                                                                "L"))))
                                                .andExpect(status().isCreated())
                                                .andReturn();
                                UUID lesId =
                                        UUID.fromString(
                                                (String)
                                                        toMap(
                                                                        lessonRes
                                                                                .getResponse()
                                                                                .getContentAsString())
                                                                .get("id"));
                                UUID lesRev =
                                        UUID.fromString(
                                                (String)
                                                        toMap(
                                                                        lessonRes
                                                                                .getResponse()
                                                                                .getContentAsString())
                                                                .get("latestRevisionId"));

                                mockMvc.perform(
                                                post("/api/v1/admin/content/revisions/"
                                                                + lesRev
                                                                + "/submit")
                                                        .with(
                                                                SecurityMockMvcRequestPostProcessors
                                                                        .user(adminPrincipal()))
                                                        .with(csrf()))
                                        .andExpect(status().isOk());
                                mockMvc.perform(
                                                post("/api/v1/admin/content/revisions/"
                                                                + lesRev
                                                                + "/approve")
                                                        .with(
                                                                SecurityMockMvcRequestPostProcessors
                                                                        .user(adminPrincipal()))
                                                        .with(csrf()))
                                        .andExpect(status().isOk());

                                return new UUID[] {topId, topRev, lesId, lesRev};
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });

        UUID topicId = ids[0];
        UUID topicRevId = ids[1];
        UUID lessonId = ids[2];
        UUID lessonRevId = ids[3];

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Long> publishPidHolder = new AtomicReference<>();
        AtomicReference<Long> archivePidHolder = new AtomicReference<>();
        CountDownLatch publishAcquiredLatch = new CountDownLatch(1);
        CountDownLatch publishReleaseLatch = new CountDownLatch(1);
        CountDownLatch archiveStartedLatch = new CountDownLatch(1);
        try {
            // Thread 1: Publish Transaction publishes lesson and holds transaction uncommitted
            // (holding FOR SHARE of topic)
            Future<ContentRevisionResponse> publishFuture =
                    executor.submit(
                            () -> {
                                return transactionTemplate.execute(
                                        status -> {
                                            Long pid =
                                                    jdbcTemplate.queryForObject(
                                                            "SELECT pg_backend_pid()", Long.class);
                                            publishPidHolder.set(pid);
                                            ContentRevisionResponse res =
                                                    authoringService.publish(
                                                            lessonRevId, contentAdminUser.getId());
                                            publishAcquiredLatch.countDown();
                                            try {
                                                assertThat(
                                                                publishReleaseLatch.await(
                                                                        10, TimeUnit.SECONDS))
                                                        .isTrue();
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                                throw new RuntimeException(e);
                                            }
                                            return res;
                                        });
                            });

            // Ensure Thread 1 has acquired locks (FOR SHARE of topic and FOR UPDATE of lesson)
            assertThat(publishAcquiredLatch.await(5, TimeUnit.SECONDS)).isTrue();
            Long publishPid = publishPidHolder.get();
            assertThat(publishPid).isNotNull();

            // Thread 2: Archive Transaction starts while publication lock is still held
            Future<Void> archiveFuture =
                    executor.submit(
                            () -> {
                                transactionTemplate.execute(
                                        status -> {
                                            Long pid =
                                                    jdbcTemplate.queryForObject(
                                                            "SELECT pg_backend_pid()", Long.class);
                                            archivePidHolder.set(pid);
                                            archiveStartedLatch.countDown();
                                            authoringService.archiveItem(
                                                    topicId,
                                                    "Archive after publish",
                                                    secondContentAdminUser.getId());
                                            return null;
                                        });
                                return null;
                            });

            assertThat(archiveStartedLatch.await(5, TimeUnit.SECONDS)).isTrue();
            Long archivePid = archivePidHolder.get();
            assertThat(archivePid).isNotNull();

            // Verify Thread 2 (archive) is actively blocked by Thread 1 (publisher PID) in
            // PostgreSQL
            assertThat(awaitBlockedBy(archivePid, publishPid, 5)).isTrue();
            assertThat(archiveFuture.isDone()).isFalse();

            // Commit Thread 1 (Publication)
            publishReleaseLatch.countDown();
            ContentRevisionResponse pubRes = publishFuture.get(5, TimeUnit.SECONDS);
            assertThat(pubRes).isNotNull();

            // Now Thread 2 unblocks, acquires FOR UPDATE on topic, and completes archive
            archiveFuture.get(5, TimeUnit.SECONDS);

            // Invariant assertions: Lesson refs/pointer/projection atomic before archive
            ContentItem lessonItem = itemRepository.findById(lessonId).orElseThrow();
            assertThat(lessonItem.getPublishedRevisionId()).isEqualTo(lessonRevId);

            ContentRevision publishedRev = revisionRepository.findById(lessonRevId).orElseThrow();
            assertThat(publishedRev.getStatus()).isEqualTo("PUBLISHED");
            assertThat(publishedRev.getSnapshot()).isNotNull();
            assertThat(publishedRev.getSnapshot()).contains("Coordinating Conjunctions");
            assertThat(publishedRev.getSnapshot()).contains("And, but, or");

            var lessonProj = lessonRepository.findById(lessonId);
            assertThat(lessonProj).isPresent();
            assertThat(lessonProj.get().getTitle()).isEqualTo("Coordinating Conjunctions");
            assertThat(lessonProj.get().getTopicId()).isEqualTo(topicId);

            var deps = dependencyRepository.findByIdParentRevisionId(lessonRevId);
            assertThat(deps).hasSize(1);
            assertThat(deps.get(0).getChildRevisionId()).isEqualTo(topicRevId);

            var sourceLinks = sourceLinkRepository.findByIdRevisionId(lessonRevId);
            assertThat(sourceLinks).hasSize(1);
            assertThat(sourceLinks.get(0).getSourceSnapshot()).isNotNull();

            Integer auditCount =
                    jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM audit_logs WHERE entity_id = ? AND action = 'CONTENT_PUBLISHED'",
                            Integer.class,
                            lessonId);
            assertThat(auditCount).isEqualTo(1);

            ContentItem topicItem = itemRepository.findById(topicId).orElseThrow();
            assertThat(topicItem.isArchived()).isTrue();
        } finally {
            publishReleaseLatch.countDown();
            boolean terminated = false;
            try {
                executor.shutdown();
                terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
                if (!terminated) {
                    executor.shutdownNow();
                    terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                org.junit.jupiter.api.Assertions.fail(
                        "Executor interrupted while waiting for worker termination", e);
            }
            if (!terminated) {
                org.junit.jupiter.api.Assertions.fail(
                        "Executor failed to terminate workers within bounded timeout");
            }
            transactionTemplate.execute(
                    status -> {
                        jdbcTemplate.update("DELETE FROM grammar_lessons WHERE id = ?", lessonId);
                        jdbcTemplate.update("DELETE FROM grammar_topics WHERE id = ?", topicId);
                        jdbcTemplate.update(
                                "UPDATE content_items SET is_archived = true, archived_at = CURRENT_TIMESTAMP WHERE id IN (?, ?)",
                                topicId,
                                lessonId);
                        return null;
                    });
        }
    }

    // =========================================================================
    // G5 / AC04, AC25: Filter by domain=GRAMMAR
    // =========================================================================

    @Test
    @DisplayName(
            "G5: GET /items?domain=GRAMMAR lọc đúng GRAMMAR_TOPIC và GRAMMAR_LESSON, không lẫn WORD/DECK")
    void domainFilterItems() throws Exception {
        // Create 1 Topic and 1 Lesson
        mockMvc.perform(
                        post("/api/v1/admin/content/grammar/topics")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateGrammarDraftRequest(
                                                        Map.of("name", "Adjectives"),
                                                        List.of(defaultSource.getId()),
                                                        "Topic"))))
                .andExpect(status().isCreated());

        mockMvc.perform(
                        post("/api/v1/admin/content/grammar/lessons")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateGrammarDraftRequest(
                                                        Map.of("title", "Comparative Adjectives"),
                                                        List.of(defaultSource.getId()),
                                                        "Lesson"))))
                .andExpect(status().isCreated());

        // Filter domain=GRAMMAR
        mockMvc.perform(
                        get("/api/v1/admin/content/items?domain=GRAMMAR")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[?(@.module == 'VOCABULARY')]").doesNotExist())
                .andExpect(jsonPath("$.items[?(@.type == 'GRAMMAR_TOPIC')]").exists())
                .andExpect(jsonPath("$.items[?(@.type == 'GRAMMAR_LESSON')]").exists());

        // Filter domain=VOCABULARY
        mockMvc.perform(
                        get("/api/v1/admin/content/items?domain=VOCABULARY")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.module == 'GRAMMAR')]").doesNotExist());

        // Conflicting domain and contentType returns empty
        mockMvc.perform(
                        get("/api/v1/admin/content/items?domain=GRAMMAR&contentType=WORD")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        // Unknown domain returns 400 Bad Request
        mockMvc.perform(
                        get("/api/v1/admin/content/items?domain=UNKNOWN")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
