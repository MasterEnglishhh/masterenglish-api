package com.masterenglish.api.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
import com.masterenglish.api.modules.admin.dto.request.CreateContentSourceRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateRevisionFromPublishedRequest;
import com.masterenglish.api.modules.admin.dto.request.ReviewDecisionRequest;
import com.masterenglish.api.modules.admin.dto.request.UpdateContentSourceRequest;
import com.masterenglish.api.modules.admin.dto.request.UpdateRevisionDraftRequest;
import com.masterenglish.api.modules.content.entity.ContentItem;
import com.masterenglish.api.modules.content.entity.ContentRevision;
import com.masterenglish.api.modules.content.entity.ContentSource;
import com.masterenglish.api.modules.content.entity.ContentSourceLink;
import com.masterenglish.api.modules.content.repository.ContentItemRepository;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceLinkRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceRepository;
import com.masterenglish.api.modules.content.service.ContentAuthoringService;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.RoleRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.vocabulary.repository.WordRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@org.junit.jupiter.api.condition.EnabledIf("isDockerAvailable")
class ContentEditingIntegrationTests {

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
    @Autowired private ContentAuthoringService authoringService;
    @Autowired private WordRepository wordRepository;

    @Autowired
    private com.masterenglish.api.modules.content.repository.AuditLogRepository auditLogRepository;

    @Autowired
    private com.masterenglish.api.modules.vocabulary.repository.DeckRepository deckRepository;

    @Autowired
    private com.masterenglish.api.modules.content.repository.RevisionDependencyRepository
            dependencyRepository;

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private User contentAdminUser;
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

        learnerUser = new User("learner-" + testId + "@test.com", "hash", "Learner", "User");
        learnerUser.addRole(learnerRole);
        learnerUser.markEmailVerified(Instant.now());
        learnerUser = userRepository.saveAndFlush(learnerUser);

        defaultSource =
                sourceRepository.saveAndFlush(
                        new ContentSource(
                                UUID.randomUUID(),
                                "Oxford English Dictionary 12th",
                                "EXTERNAL",
                                "https://oed.com",
                                "Oxford Press",
                                "EDITORIAL_CITATION",
                                null,
                                "Used for definitions",
                                "Standard citation permission",
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

    @Test
    @DisplayName("T1 - Admin đọc chi tiết nội dung và lịch sử revision có CAS")
    void adminReadsEditableSnapshotAndRevisionHistory() throws Exception {
        // 1. Seed published v1
        String wordSnapshot =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "lemma": "resilient",
                  "language_code": "en",
                  "ipa": "/rɪˈzɪliənt/",
                  "senses": [
                    {
                      "id": "c2000000-0000-0000-0000-000000000001",
                      "sense_no": 1,
                      "part_of_speech": "ADJECTIVE",
                      "cefr_level": "B2",
                      "meaning_vi": "kiên cường",
                      "definition_en": "able to recover quickly",
                      "examples": [
                        {
                          "id": "c3000000-0000-0000-0000-000000000001",
                          "position": 1,
                          "example_en": "He is resilient.",
                          "example_vi": "Anh ấy kiên cường."
                        }
                      ]
                    }
                  ]
                }
                """;

        UUID itemId = UUID.randomUUID();
        var itemResp =
                authoringService.createItemWithDraft(
                        itemId,
                        "WORD",
                        contentAdminUser.getId(),
                        "admin",
                        "word:resilient",
                        wordSnapshot,
                        "v1 draft",
                        defaultSource.getId());
        List<ContentRevision> revs =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(itemId);
        UUID v1Id = revs.get(0).getId();
        authoringService.submitForReview(v1Id, contentAdminUser.getId());
        authoringService.approve(v1Id, contentAdminUser.getId(), "Approved v1");
        authoringService.publish(v1Id, contentAdminUser.getId());

        // 2. Clone v1 to create open v2
        mockMvc.perform(
                        post("/api/v1/admin/content/items/{id}/revisions", itemId)
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateRevisionFromPublishedRequest(
                                                        v1Id, null, "Creating v2 for editing"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(2))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // 3. GET /api/v1/admin/content/items/{id} detail
        mockMvc.perform(
                        get("/api/v1/admin/content/items/{id}", itemId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(itemId.toString()))
                .andExpect(jsonPath("$.contentType").value("WORD"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.versionNo").value(2))
                .andExpect(jsonPath("$.version").isNumber())
                .andExpect(jsonPath("$.itemVersion").isNumber())
                .andExpect(jsonPath("$.data.lemma").value("resilient"))
                .andExpect(jsonPath("$.sourceIds[0]").value(defaultSource.getId().toString()));

        // 4. GET /api/v1/admin/content/items/{id}/revisions history
        mockMvc.perform(
                        get("/api/v1/admin/content/items/{id}/revisions", itemId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].versionNo").value(2))
                .andExpect(jsonPath("$.items[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.items[1].versionNo").value(1))
                .andExpect(jsonPath("$.items[1].status").value("PUBLISHED"));
    }

    @Test
    @DisplayName(
            "T1 - Content detail và revisions từ chối learner, unauthenticated và unknown item")
    void contentDetailRejectsLearnerAndUnknownItem() throws Exception {
        UUID unknownId = UUID.randomUUID();

        // 401 unauthenticated
        mockMvc.perform(get("/api/v1/admin/content/items/{id}", unknownId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/admin/content/items/{id}/revisions", unknownId))
                .andExpect(status().isUnauthorized());

        // 403 learner
        mockMvc.perform(
                        get("/api/v1/admin/content/items/{id}", unknownId)
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                learnerPrincipal())))
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        get("/api/v1/admin/content/items/{id}/revisions", unknownId)
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                learnerPrincipal())))
                .andExpect(status().isForbidden());

        // 404 unknown item
        mockMvc.perform(
                        get("/api/v1/admin/content/items/{id}", unknownId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(
                        get("/api/v1/admin/content/items/{id}/revisions", unknownId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("T2 - Stale draft version CAS không ghi đè snapshot")
    void staleDraftVersionDoesNotOverwriteSnapshot() throws Exception {
        // Create draft word
        String initialData =
                """
                {
                  "lemma": "persevere",
                  "language_code": "en",
                  "senses": [
                    {
                      "id": "c2000000-0000-0000-0000-000000000002",
                      "sense_no": 1,
                      "part_of_speech": "VERB",
                      "cefr_level": "B2",
                      "meaning_vi": "kiên trì",
                      "examples": []
                    }
                  ]
                }
                """;

        UUID itemId = UUID.randomUUID();
        authoringService.createItemWithDraft(
                itemId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:persevere",
                initialData,
                "draft v1",
                defaultSource.getId());

        ContentRevision rev =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(itemId).get(0);
        long initialVersion = rev.getVersion();

        // 1. Successful update with expectedVersion == initialVersion
        String updatedData =
                """
                {
                  "lemma": "persevere",
                  "language_code": "en",
                  "senses": [
                    {
                      "id": "c2000000-0000-0000-0000-000000000002",
                      "sense_no": 1,
                      "part_of_speech": "VERB",
                      "cefr_level": "B2",
                      "meaning_vi": "kiên trì, bền chí",
                      "examples": []
                    }
                  ]
                }
                """;

        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        initialVersion,
                                                        toMap(updatedData),
                                                        List.of(defaultSource.getId()),
                                                        "Cập nhật nghĩa tiếng Việt"))))
                .andExpect(status().isOk());

        // 2. Stale update with same initialVersion (which is now outdated) -> 409 Conflict
        String staleData =
                """
                {
                  "lemma": "persevere",
                  "language_code": "en",
                  "senses": [
                    {
                      "id": "c2000000-0000-0000-0000-000000000002",
                      "sense_no": 1,
                      "part_of_speech": "VERB",
                      "cefr_level": "B2",
                      "meaning_vi": "GHI ĐÈ LỖI STALE",
                      "examples": []
                    }
                  ]
                }
                """;

        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        initialVersion,
                                                        toMap(staleData),
                                                        List.of(defaultSource.getId()),
                                                        "Stale update attempt"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 3. Verify snapshot in DB was NOT overwritten with stale data
        ContentRevision currentRev = revisionRepository.findById(rev.getId()).orElseThrow();
        assertThat(currentRev.getSnapshot()).contains("kiên trì, bền chí");
        assertThat(currentRev.getSnapshot()).doesNotContain("GHI ĐÈ LỖI STALE");
    }

    @Test
    @DisplayName(
            "T2 - Sửa REJECTED hoặc APPROVED reset về DRAFT và cần duyệt lại trước khi publish")
    void rejectedAndApprovedEditsRequireFreshReview() throws Exception {
        UUID itemId = UUID.randomUUID();
        String initialData =
                """
                {
                  "lemma": "diligent",
                  "language_code": "en",
                  "senses": [
                    {
                      "id": "c2000000-0000-0000-0000-000000000003",
                      "sense_no": 1,
                      "part_of_speech": "ADJECTIVE",
                      "cefr_level": "B1",
                      "meaning_vi": "chăm chỉ",
                      "examples": []
                    }
                  ]
                }
                """;

        authoringService.createItemWithDraft(
                itemId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:diligent",
                initialData,
                "draft v1",
                defaultSource.getId());

        ContentRevision rev =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(itemId).get(0);

        // 1. Submit -> IN_REVIEW
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/{id}/submit", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_REVIEW"));

        // While IN_REVIEW, edit is blocked
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        rev.getVersion(),
                                                        toMap(initialData),
                                                        List.of(defaultSource.getId()),
                                                        "Editing in review"))))
                .andExpect(status().isConflict());

        // 2. Reject revision
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/{id}/reject", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new ReviewDecisionRequest("Thiếu ví dụ minh họa"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewNote").value("Thiếu ví dụ minh họa"));

        // Cannot publish REJECTED
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/{id}/publish", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isConflict());

        // 3. Edit REJECTED draft -> resets to DRAFT and clears review metadata
        rev = revisionRepository.findById(rev.getId()).orElseThrow();
        String withExampleData =
                """
                {
                  "lemma": "diligent",
                  "language_code": "en",
                  "senses": [
                    {
                      "id": "c2000000-0000-0000-0000-000000000003",
                      "sense_no": 1,
                      "part_of_speech": "ADJECTIVE",
                      "cefr_level": "B1",
                      "meaning_vi": "chăm chỉ, cần cù",
                      "examples": [
                        {
                          "id": "c3000000-0000-0000-0000-000000000003",
                          "position": 1,
                          "example_en": "She is a diligent student.",
                          "example_vi": "Cô ấy là một học sinh chăm chỉ."
                        }
                      ]
                    }
                  ]
                }
                """;

        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        rev.getVersion(),
                                                        toMap(withExampleData),
                                                        List.of(defaultSource.getId()),
                                                        "Bổ sung ví dụ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.reviewNote").doesNotExist());

        // 4. Submit -> Approve
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/{id}/submit", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk());

        var approveAction =
                mockMvc.perform(
                                post("/api/v1/admin/content/revisions/{id}/approve", rev.getId())
                                        .with(csrf())
                                        .with(
                                                SecurityMockMvcRequestPostProcessors.user(
                                                        adminPrincipal()))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        new ReviewDecisionRequest("Đã duyệt"))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("APPROVED"))
                        .andReturn();

        long approvedVersion =
                ((Number)
                                com.jayway.jsonpath.JsonPath.read(
                                        approveAction.getResponse().getContentAsString(),
                                        "$.version"))
                        .longValue();

        // 5. Editing APPROVED revision resets to DRAFT
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        approvedVersion,
                                                        toMap(withExampleData),
                                                        List.of(defaultSource.getId()),
                                                        "Chỉnh sửa sau approve"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // Cannot publish now without fresh submit & approval
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/{id}/publish", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isConflict());

        // Re-submit -> Re-approve -> Publish
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/{id}/submit", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/{id}/approve", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/{id}/publish", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    @DisplayName(
            "T2 - Tạo revision mới từ Published giữ nguyên lịch sử v1 và chỉ cho phép 1 open draft")
    void publishedRevisionCreatesOneNewDraftAndPreservesHistory() throws Exception {
        UUID itemId = UUID.randomUUID();
        String initialData =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "lemma": "tenacious",
                  "language_code": "en",
                  "senses": [
                    {
                      "id": "c2000000-0000-0000-0000-000000000004",
                      "sense_no": 1,
                      "part_of_speech": "ADJECTIVE",
                      "cefr_level": "C1",
                      "meaning_vi": "ngoan cường",
                      "examples": []
                    }
                  ]
                }
                """;

        authoringService.createItemWithDraft(
                itemId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:tenacious",
                initialData,
                "v1",
                defaultSource.getId());

        ContentRevision v1 =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(itemId).get(0);
        authoringService.submitForReview(v1.getId(), contentAdminUser.getId());
        authoringService.approve(v1.getId(), contentAdminUser.getId(), "OK");
        authoringService.publish(v1.getId(), contentAdminUser.getId());

        ContentItem item = itemRepository.findById(itemId).orElseThrow();
        long itemVersion = item.getVersion();

        // 1. Clone Published v1 -> creates new Draft v2
        mockMvc.perform(
                        post("/api/v1/admin/content/items/{id}/revisions", itemId)
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateRevisionFromPublishedRequest(
                                                        v1.getId(), itemVersion, "Bắt đầu v2"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(2))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // 2. V1 snapshot and source links remain immutable
        ContentRevision v1After = revisionRepository.findById(v1.getId()).orElseThrow();
        assertThat(v1After.getStatus()).isEqualTo("PUBLISHED");
        assertThat(v1After.getVersionNo()).isEqualTo(1);
        assertThat(sourceLinkRepository.findByIdRevisionId(v1.getId())).isNotEmpty();

        // 3. Second clone attempt while v2 is still open -> 409 Conflict
        mockMvc.perform(
                        post("/api/v1/admin/content/items/{id}/revisions", itemId)
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateRevisionFromPublishedRequest(
                                                        v1.getId(), null, "Second clone attempt"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("T2 - Draft edit bảo toàn quyền sở hữu sense và example giữa các từ")
    void draftEditPreservesSenseAndExampleOwnership() throws Exception {
        // Publish Word 1 with sense S1
        UUID word1Id = UUID.randomUUID();
        UUID sense1Id = UUID.fromString("c2000000-0000-0000-0000-000000000010");
        String word1Data =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "lemma": "lucid",
                  "language_code": "en",
                  "senses": [
                    {
                      "id": "c2000000-0000-0000-0000-000000000010",
                      "sense_no": 1,
                      "part_of_speech": "ADJECTIVE",
                      "cefr_level": "B2",
                      "meaning_vi": "rõ ràng, minh bạch",
                      "examples": []
                    }
                  ]
                }
                """;
        authoringService.createItemWithDraft(
                word1Id,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:lucid",
                word1Data,
                "v1",
                defaultSource.getId());
        ContentRevision w1Rev =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(word1Id).get(0);
        authoringService.submitForReview(w1Rev.getId(), contentAdminUser.getId());
        authoringService.approve(w1Rev.getId(), contentAdminUser.getId(), "OK");
        authoringService.publish(w1Rev.getId(), contentAdminUser.getId());

        // Create Word 2 draft
        UUID word2Id = UUID.randomUUID();
        String word2Data =
                """
                {
                  "lemma": "coherent",
                  "language_code": "en",
                  "senses": [
                    {
                      "id": "c2000000-0000-0000-0000-000000000020",
                      "sense_no": 1,
                      "part_of_speech": "ADJECTIVE",
                      "cefr_level": "B2",
                      "meaning_vi": "mạch lạc",
                      "examples": []
                    }
                  ]
                }
                """;
        authoringService.createItemWithDraft(
                word2Id,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:coherent",
                word2Data,
                "v1",
                defaultSource.getId());
        ContentRevision w2Rev =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(word2Id).get(0);

        // Attempt to update Word 2 using sense1Id which belongs to Word 1
        String stolenSenseData =
                """
                {
                  "lemma": "coherent",
                  "language_code": "en",
                  "senses": [
                    {
                      "id": "c2000000-0000-0000-0000-000000000010",
                      "sense_no": 1,
                      "part_of_speech": "ADJECTIVE",
                      "cefr_level": "B2",
                      "meaning_vi": "mạch lạc",
                      "examples": []
                    }
                  ]
                }
                """;

        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", w2Rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        w2Rev.getVersion(),
                                                        toMap(stolenSenseData),
                                                        List.of(defaultSource.getId()),
                                                        "Attempt sense move"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("T2 - Thao tác ghi yêu cầu CSRF, quyền CONTENT_ADMIN và kiểm tra định dạng")
    void writeRequiresCsrfAndContentRole() throws Exception {
        UUID randomRevId = UUID.randomUUID();

        // Missing CSRF -> 403
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", randomRevId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedVersion\":1,\"data\":{},\"sourceIds\":[]}"))
                .andExpect(status().isForbidden());

        // Learner role -> 403
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", randomRevId)
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(learnerPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedVersion\":1,\"data\":{},\"sourceIds\":[]}"))
                .andExpect(status().isForbidden());

        // Unknown revision ID -> 404
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", randomRevId)
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedVersion\":1,\"data\":{},\"sourceIds\":[]}"))
                .andExpect(status().isNotFound());

        // Body with disallowed fields (status, createdBy) -> 400
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", randomRevId)
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"expectedVersion\":1,\"data\":{},\"sourceIds\":[],\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isBadRequest());

        // Negative expectedVersion -> 400
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", randomRevId)
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedVersion\":-1,\"data\":{},\"sourceIds\":[]}"))
                .andExpect(status().isBadRequest());

        // Negative expectedItemVersion in clone -> 400
        mockMvc.perform(
                        post("/api/v1/admin/content/items/{id}/revisions", UUID.randomUUID())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedItemVersion\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("T3 - Sắp xếp thành viên Deck, từ chối duplicate và nghĩa chưa xuất bản")
    void deckEditReordersAndRejectsDuplicates() throws Exception {
        // Publish Word 1 (S1) and Word 2 (S2)
        UUID s1 = UUID.fromString("c2000000-0000-0000-0000-000000000031");
        UUID s2 = UUID.fromString("c2000000-0000-0000-0000-000000000032");

        String w1 =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "lemma": "deckword1",
                  "language_code": "en",
                  "senses": [{"id":"c2000000-0000-0000-0000-000000000031","sense_no":1,"part_of_speech":"NOUN","cefr_level":"A1","meaning_vi":"từ 1","examples":[]}]
                }
                """;
        UUID item1 = UUID.randomUUID();
        authoringService.createItemWithDraft(
                item1,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:deck1",
                w1,
                "v1",
                defaultSource.getId());
        UUID r1 = revisionRepository.findByContentItemIdOrderByVersionNoDesc(item1).get(0).getId();
        authoringService.submitForReview(r1, contentAdminUser.getId());
        authoringService.approve(r1, contentAdminUser.getId(), "OK");
        authoringService.publish(r1, contentAdminUser.getId());

        String w2 =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "lemma": "deckword2",
                  "language_code": "en",
                  "senses": [{"id":"c2000000-0000-0000-0000-000000000032","sense_no":1,"part_of_speech":"NOUN","cefr_level":"A1","meaning_vi":"từ 2","examples":[]}]
                }
                """;
        UUID item2 = UUID.randomUUID();
        authoringService.createItemWithDraft(
                item2,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:deck2",
                w2,
                "v1",
                defaultSource.getId());
        UUID r2 = revisionRepository.findByContentItemIdOrderByVersionNoDesc(item2).get(0).getId();
        authoringService.submitForReview(r2, contentAdminUser.getId());
        authoringService.approve(r2, contentAdminUser.getId(), "OK");
        authoringService.publish(r2, contentAdminUser.getId());

        // Create DECK draft with S1
        UUID deckId = UUID.randomUUID();
        authoringService.createItemWithDraft(
                deckId,
                "DECK",
                contentAdminUser.getId(),
                "admin",
                "deck:test",
                "{\"title\":\"Test Deck\",\"members\":[{\"word_sense_id\":\"" + s1 + "\"}]}",
                "init deck",
                defaultSource.getId());

        ContentRevision deckRev =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(deckId).get(0);

        // 1. Duplicate sense in deck edit -> 400
        String dupMembers =
                "{\"title\":\"Test Deck\",\"members\":[{\"word_sense_id\":\""
                        + s1
                        + "\"},{\"word_sense_id\":\""
                        + s1
                        + "\"}]}";
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", deckRev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        deckRev.getVersion(),
                                                        toMap(dupMembers),
                                                        List.of(defaultSource.getId()),
                                                        "Duplicate member"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        // 2. Unpublished sense -> 400
        UUID unpubSense = UUID.randomUUID();
        String unpubMembers =
                "{\"title\":\"Test Deck\",\"members\":[{\"word_sense_id\":\""
                        + unpubSense
                        + "\"}]}";
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", deckRev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        deckRev.getVersion(),
                                                        toMap(unpubMembers),
                                                        List.of(defaultSource.getId()),
                                                        "Unpublished member"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        // 3. Valid reorder: [S2, S1] -> canonical positions 1, 2
        String validReorder =
                "{\"title\":\"Test Deck Reordered\",\"members\":[{\"word_sense_id\":\""
                        + s2
                        + "\"},{\"word_sense_id\":\""
                        + s1
                        + "\"}]}";
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", deckRev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        deckRev.getVersion(),
                                                        toMap(validReorder),
                                                        List.of(defaultSource.getId()),
                                                        "Valid reorder"))))
                .andExpect(status().isOk());

        ContentRevision updated = revisionRepository.findById(deckRev.getId()).orElseThrow();
        JsonNode data = objectMapper.readTree(updated.getSnapshot()).path("data");
        assertThat(data.path("members").get(0).path("word_sense_id").asText())
                .isEqualTo(s2.toString());
        assertThat(data.path("members").get(0).path("position").asInt()).isEqualTo(1);
        assertThat(data.path("members").get(1).path("word_sense_id").asText())
                .isEqualTo(s1.toString());
        assertThat(data.path("members").get(1).path("position").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("T3 - Cập nhật source không ghi đè source_snapshot đã xuất bản")
    void sourceChangesDoNotRewritePublishedAttribution() throws Exception {
        // 1. Create source
        ContentSource src =
                sourceRepository.saveAndFlush(
                        new ContentSource(
                                UUID.randomUUID(),
                                "Original Citation",
                                "EXTERNAL",
                                "https://original.com",
                                "Original Author",
                                "CC-BY",
                                null,
                                "Original attribution",
                                "Permission granted",
                                "ACTIVE",
                                contentAdminUser.getId()));

        // 2. Publish Word with this source
        UUID wordId = UUID.randomUUID();
        String wordData =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "lemma": "immutable",
                  "language_code": "en",
                  "senses": [{"id":"c2000000-0000-0000-0000-000000000041","sense_no":1,"part_of_speech":"ADJECTIVE","cefr_level":"C1","meaning_vi":"bất biến","examples":[]}]
                }
                """;
        authoringService.createItemWithDraft(
                wordId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:immutable",
                wordData,
                "v1",
                src.getId());
        ContentRevision rev =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(wordId).get(0);
        authoringService.submitForReview(rev.getId(), contentAdminUser.getId());
        authoringService.approve(rev.getId(), contentAdminUser.getId(), "OK");
        authoringService.publish(rev.getId(), contentAdminUser.getId());

        ContentSourceLink linkBefore = sourceLinkRepository.findByIdRevisionId(rev.getId()).get(0);
        assertThat(linkBefore.getSourceSnapshot()).contains("Original Citation");

        // 3. Update source via PUT /api/v1/admin/content/sources/{id}
        mockMvc.perform(
                        put("/api/v1/admin/content/sources/{id}", src.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateContentSourceRequest(
                                                        "Completely Changed Citation",
                                                        "EXTERNAL",
                                                        "https://changed.com",
                                                        "New Author",
                                                        "CC-BY",
                                                        null,
                                                        "New attribution",
                                                        "New note",
                                                        "ARCHIVED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Completely Changed Citation"))
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        // 4. Historical snapshot on published revision must NOT change!
        ContentSourceLink linkAfter = sourceLinkRepository.findByIdRevisionId(rev.getId()).get(0);
        assertThat(linkAfter.getSourceSnapshot()).contains("Original Citation");
        assertThat(linkAfter.getSourceSnapshot()).doesNotContain("Completely Changed Citation");
    }

    @Test
    @DisplayName("T3 - Preview / validate là thao tác read-only không thay đổi trạng thái hay DB")
    void previewValidationIsReadOnly() throws Exception {
        long wordsBefore = wordRepository.count();
        long decksBefore = deckRepository.count();
        long auditBefore = auditLogRepository.count();

        // 1. Valid Word
        UUID wordId = UUID.randomUUID();
        String draftData =
                """
                {
                  "lemma": "validate_only",
                  "language_code": "en",
                  "senses": [{"id":"c2000000-0000-0000-0000-000000000051","sense_no":1,"part_of_speech":"VERB","cefr_level":"B2","meaning_vi":"xác thực","examples":[]}]
                }
                """;
        authoringService.createItemWithDraft(
                wordId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:val",
                draftData,
                "v1",
                defaultSource.getId());
        ContentRevision rev =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(wordId).get(0);

        long versionBefore = rev.getVersion();
        String statusBefore = rev.getStatus();
        String snapshotBefore = rev.getSnapshot();

        // Validate draft
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/{id}/validate", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errors").isEmpty());

        // Check revision did not mutate
        ContentRevision revAfter = revisionRepository.findById(rev.getId()).orElseThrow();
        assertThat(revAfter.getVersion()).isEqualTo(versionBefore);
        assertThat(revAfter.getStatus()).isEqualTo(statusBefore);
        assertThat(revAfter.getSnapshot()).isEqualTo(snapshotBefore);
        assertThat(itemRepository.findById(wordId).get().getPublishedRevisionId()).isNull();
        assertThat(sourceLinkRepository.findByIdRevisionId(rev.getId()).get(0).getSourceSnapshot())
                .isNull();

        // 2. Invalid Word: unsupported audio media
        UUID invalidWordId = UUID.randomUUID();
        String invalidWordData =
                """
                {
                  "lemma": "invalid_media_word",
                  "language_code": "en",
                  "audio_media_id": "c1000000-0000-0000-0000-000000000001",
                  "senses": [{"id":"c2000000-0000-0000-0000-000000000052","sense_no":1,"part_of_speech":"VERB","cefr_level":"B2","meaning_vi":"xác thực lỗi","examples":[]}]
                }
                """;
        authoringService.createItemWithDraft(
                invalidWordId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:inv_media",
                invalidWordData,
                "v1",
                defaultSource.getId());
        ContentRevision invalidWordRev =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(invalidWordId).get(0);

        long invWordVerBefore = invalidWordRev.getVersion();
        String invWordStatusBefore = invalidWordRev.getStatus();
        String invWordSnapBefore = invalidWordRev.getSnapshot();

        mockMvc.perform(
                        post(
                                        "/api/v1/admin/content/revisions/{id}/validate",
                                        invalidWordRev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].field").value("data"))
                .andExpect(
                        jsonPath("$.errors[0].message")
                                .value("Audio authoring is not enabled in this text-only slice."));

        ContentRevision invWordRevAfter =
                revisionRepository.findById(invalidWordRev.getId()).orElseThrow();
        assertThat(invWordRevAfter.getVersion()).isEqualTo(invWordVerBefore);
        assertThat(invWordRevAfter.getStatus()).isEqualTo(invWordStatusBefore);
        assertThat(invWordRevAfter.getSnapshot()).isEqualTo(invWordSnapBefore);

        // 3. Invalid Deck: empty members
        UUID invalidDeckId = UUID.randomUUID();
        authoringService.createItemWithDraft(
                invalidDeckId,
                "DECK",
                contentAdminUser.getId(),
                "admin",
                "deck:empty_members",
                "{\"title\":\"Empty Deck\",\"members\":[]}",
                "v1",
                defaultSource.getId());
        ContentRevision invalidDeckRev =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(invalidDeckId).get(0);

        long invDeckVerBefore = invalidDeckRev.getVersion();
        String invDeckStatusBefore = invalidDeckRev.getStatus();
        String invDeckSnapBefore = invalidDeckRev.getSnapshot();

        mockMvc.perform(
                        post(
                                        "/api/v1/admin/content/revisions/{id}/validate",
                                        invalidDeckRev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[?(@.field == 'members')]").exists())
                .andExpect(
                        jsonPath("$.errors[?(@.field == 'members')].message")
                                .value("Deck must contain at least one member."));

        ContentRevision invDeckRevAfter =
                revisionRepository.findById(invalidDeckRev.getId()).orElseThrow();
        assertThat(invDeckRevAfter.getVersion()).isEqualTo(invDeckVerBefore);
        assertThat(invDeckRevAfter.getStatus()).isEqualTo(invDeckStatusBefore);
        assertThat(invDeckRevAfter.getSnapshot()).isEqualTo(invDeckSnapBefore);

        // Word and Deck repository projections were NOT written
        assertThat(wordRepository.count()).isEqualTo(wordsBefore);
        assertThat(deckRepository.count()).isEqualTo(decksBefore);

        // Independent DB assertions for invariant T3 read-only proof:
        // 1. Published pointer remains null for all tested items
        assertThat(itemRepository.findById(wordId).orElseThrow().getPublishedRevisionId()).isNull();
        assertThat(itemRepository.findById(invalidWordId).orElseThrow().getPublishedRevisionId())
                .isNull();
        assertThat(itemRepository.findById(invalidDeckId).orElseThrow().getPublishedRevisionId())
                .isNull();

        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT published_revision_id FROM content_items WHERE id = ?",
                                String.class,
                                wordId))
                .isNull();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT published_revision_id FROM content_items WHERE id = ?",
                                String.class,
                                invalidWordId))
                .isNull();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT published_revision_id FROM content_items WHERE id = ?",
                                String.class,
                                invalidDeckId))
                .isNull();

        // 2. Source snapshots remain null (not captured during validate)
        assertThat(sourceLinkRepository.findByIdRevisionId(rev.getId()).get(0).getSourceSnapshot())
                .isNull();
        assertThat(
                        sourceLinkRepository
                                .findByIdRevisionId(invalidWordRev.getId())
                                .get(0)
                                .getSourceSnapshot())
                .isNull();
        assertThat(
                        sourceLinkRepository
                                .findByIdRevisionId(invalidDeckRev.getId())
                                .get(0)
                                .getSourceSnapshot())
                .isNull();

        // 3. Validate calls (both valid and invalid) generated ZERO audit logs
        // (audit count before validate calls equals audit count after validate calls)
        long auditAfterDraftCreation = auditBefore + 3; // Exactly 3 drafts created
        assertThat(auditLogRepository.count()).isEqualTo(auditAfterDraftCreation);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs", Long.class))
                .isEqualTo(auditAfterDraftCreation);
    }

    @Test
    @DisplayName(
            "C2 / AC13 - Update source kiểm tra URL hợp lệ, status và sourceType theo contract")
    void sourceUpdateValidatesUrlsAndStatusAndSourceType() throws Exception {
        ContentSource src =
                sourceRepository.saveAndFlush(
                        new ContentSource(
                                UUID.randomUUID(),
                                "Validation Source",
                                "EXTERNAL",
                                "https://source.com",
                                "Author",
                                "CC-BY",
                                null,
                                "Attribution",
                                "Note",
                                "ACTIVE",
                                contentAdminUser.getId()));

        long auditCountBefore = auditLogRepository.count();

        // 1. sourceUrl with javascript scheme -> 400
        mockMvc.perform(
                        put("/api/v1/admin/content/sources/{id}", src.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateContentSourceRequest(
                                                        "Title",
                                                        "EXTERNAL",
                                                        "javascript:alert(1)",
                                                        "Author",
                                                        "CC-BY",
                                                        null,
                                                        "Attr",
                                                        "Note",
                                                        "ACTIVE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[?(@.field == 'sourceUrl')]").exists());

        // 2. licenseUrl with javascript scheme -> 400
        mockMvc.perform(
                        put("/api/v1/admin/content/sources/{id}", src.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateContentSourceRequest(
                                                        "Title",
                                                        "EXTERNAL",
                                                        "https://valid.com",
                                                        "Author",
                                                        "CC-BY",
                                                        "javascript:void(0)",
                                                        "Attr",
                                                        "Note",
                                                        "ACTIVE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[?(@.field == 'licenseUrl')]").exists());

        // 3. status not in ('ACTIVE', 'ARCHIVED') -> 400
        mockMvc.perform(
                        put("/api/v1/admin/content/sources/{id}", src.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateContentSourceRequest(
                                                        "Title",
                                                        "EXTERNAL",
                                                        "https://valid.com",
                                                        "Author",
                                                        "CC-BY",
                                                        null,
                                                        "Attr",
                                                        "Note",
                                                        "BOGUS_STATUS"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[?(@.field == 'status')]").exists());

        // 4. sourceType not in ('SELF_AUTHORED', 'EXTERNAL') -> 400
        mockMvc.perform(
                        put("/api/v1/admin/content/sources/{id}", src.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateContentSourceRequest(
                                                        "Title",
                                                        "INVALID_TYPE",
                                                        "https://valid.com",
                                                        "Author",
                                                        "CC-BY",
                                                        null,
                                                        "Attr",
                                                        "Note",
                                                        "ACTIVE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[?(@.field == 'sourceType')]").exists());

        // Assert DB unchanged and no audit logged
        ContentSource srcAfter = sourceRepository.findById(src.getId()).orElseThrow();
        assertThat(srcAfter.getTitle()).isEqualTo("Validation Source");
        assertThat(srcAfter.getSourceUrl()).isEqualTo("https://source.com");
        assertThat(srcAfter.getStatus()).isEqualTo("ACTIVE");
        assertThat(auditLogRepository.count()).isEqualTo(auditCountBefore);

        // 5. Valid HTTP and HTTPS URLs succeed
        mockMvc.perform(
                        put("/api/v1/admin/content/sources/{id}", src.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateContentSourceRequest(
                                                        "Valid HTTP Title",
                                                        "SELF_AUTHORED",
                                                        "http://example.com/source",
                                                        "Author",
                                                        "CC-BY",
                                                        "https://example.com/license",
                                                        "Attr",
                                                        "Note",
                                                        "ACTIVE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceUrl").value("http://example.com/source"))
                .andExpect(jsonPath("$.sourceType").value("SELF_AUTHORED"));
    }

    @Test
    @DisplayName("C2 / AC13 - Xuất bản với nguồn ARCHIVED bị rollback nguyên tử trong DB")
    void publishRollsBackWhenSourceIsArchived() throws Exception {
        ContentSource src =
                sourceRepository.saveAndFlush(
                        new ContentSource(
                                UUID.randomUUID(),
                                "Archived Source",
                                "EXTERNAL",
                                "https://source.com",
                                "Author",
                                "CC-BY",
                                null,
                                "Attribution",
                                "Permission note",
                                "ACTIVE",
                                contentAdminUser.getId()));

        UUID wordId = UUID.randomUUID();
        String wordData =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "lemma": "source_rollback",
                  "language_code": "en",
                  "senses": [{"id":"c2000000-0000-0000-0000-000000000088","sense_no":1,"part_of_speech":"NOUN","cefr_level":"B2","meaning_vi":"nghĩa thử","examples":[]}]
                }
                """;
        authoringService.createItemWithDraft(
                wordId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:src_rb",
                wordData,
                "v1",
                src.getId());
        ContentRevision rev =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(wordId).get(0);
        authoringService.submitForReview(rev.getId(), contentAdminUser.getId());
        authoringService.approve(rev.getId(), contentAdminUser.getId(), "Approved");

        // Archive source
        src.update(
                src.getTitle(),
                src.getSourceType(),
                src.getSourceUrl(),
                src.getAuthor(),
                src.getLicenseCode(),
                src.getLicenseUrl(),
                src.getAttribution(),
                src.getPermissionNote(),
                "ARCHIVED");
        sourceRepository.saveAndFlush(src);

        // Attempt publish -> 400 Bad Request
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/{id}/publish", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail")
                                .value("Active source and declared permission required."));

        // Verify DB rollback
        ContentRevision revAfter = revisionRepository.findById(rev.getId()).orElseThrow();
        assertThat(revAfter.getStatus()).isEqualTo("APPROVED");
        assertThat(itemRepository.findById(wordId).get().getPublishedRevisionId()).isNull();
        assertThat(wordRepository.findById(wordId)).isEmpty();
    }

    @Test
    @DisplayName(
            "AC13/AC16/AC20 - Nguồn EXTERNAL thiếu attribution bị từ chối khi preview và publish với rollback nguyên tử")
    void externalSourceWithoutAttributionFailsPreviewAndPublishWithRollback() throws Exception {
        // 1. Setup EXTERNAL source with licenseCode but null attribution
        ContentSource extSourceNoAttr =
                sourceRepository.saveAndFlush(
                        new ContentSource(
                                UUID.randomUUID(),
                                "External Source Without Attribution",
                                "EXTERNAL",
                                "https://external.example.com",
                                "External Author",
                                "CC-BY-4.0",
                                "https://creativecommons.org/licenses/by/4.0/",
                                null, // null attribution
                                null,
                                "ACTIVE",
                                contentAdminUser.getId()));

        UUID wordId = UUID.randomUUID();
        String wordData =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "lemma": "attribution_check",
                  "language_code": "en",
                  "senses": [{"id":"c2000000-0000-0000-0000-000000000099","sense_no":1,"part_of_speech":"NOUN","cefr_level":"B1","meaning_vi":"thử nghiệm attribution","examples":[]}]
                }
                """;
        authoringService.createItemWithDraft(
                wordId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:attr_check",
                wordData,
                "v1",
                extSourceNoAttr.getId());
        ContentRevision rev =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(wordId).get(0);
        authoringService.submitForReview(rev.getId(), contentAdminUser.getId());
        authoringService.approve(
                rev.getId(), contentAdminUser.getId(), "Approved for preview/publish test");

        // 2. Preview / Validate -> valid = false, field "sources" has error
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/{id}/validate", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[?(@.field == 'sources')]").exists())
                .andExpect(
                        jsonPath("$.errors[?(@.field == 'sources')].message")
                                .value("Active source and declared permission required."));

        // 3. Publish -> 400 Bad Request
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/{id}/publish", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail")
                                .value("Active source and declared permission required."));

        // 4. Verify DB rollback and immutability
        ContentRevision revAfter = revisionRepository.findById(rev.getId()).orElseThrow();
        assertThat(revAfter.getStatus()).isEqualTo("APPROVED");
        assertThat(itemRepository.findById(wordId).get().getPublishedRevisionId()).isNull();
        assertThat(wordRepository.findById(wordId)).isEmpty();
        List<ContentSourceLink> links = sourceLinkRepository.findByIdRevisionId(rev.getId());
        assertThat(links).isNotEmpty();
        assertThat(links.get(0).getSourceSnapshot()).isNull();
        Integer pubAuditCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_logs WHERE entity_id = ? AND action = 'CONTENT_PUBLISHED'",
                        Integer.class,
                        wordId);
        assertThat(pubAuditCount).isEqualTo(0);

        // 5. Test with blank attribution ("   ") and permissionNote -> also fails preview and
        // publish
        extSourceNoAttr.update(
                extSourceNoAttr.getTitle(),
                extSourceNoAttr.getSourceType(),
                extSourceNoAttr.getSourceUrl(),
                extSourceNoAttr.getAuthor(),
                null,
                null,
                "   ", // blank attribution
                "Permission note granted",
                "ACTIVE");
        sourceRepository.saveAndFlush(extSourceNoAttr);

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/{id}/validate", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[?(@.field == 'sources')]").exists());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/{id}/publish", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.detail")
                                .value("Active source and declared permission required."));
    }

    @Test
    @DisplayName(
            "AC13/AC16/AC20 - Nguồn EXTERNAL có attribution hoặc SELF_AUTHORED không ép attribution xuất bản thành công")
    void externalSourceWithAttributionOrSelfAuthoredWithoutAttributionPublishesSuccessfully()
            throws Exception {
        // 1. Incomplete draft authoring is allowed: draft creation and update with EXTERNAL source
        // without attribution succeeds
        ContentSource draftSourceNoAttr =
                sourceRepository.saveAndFlush(
                        new ContentSource(
                                UUID.randomUUID(),
                                "Draft Incomplete External Source",
                                "EXTERNAL",
                                "https://draft.example.com",
                                "Author",
                                "CC-BY",
                                null,
                                null, // no attribution during drafting
                                null,
                                "ACTIVE",
                                contentAdminUser.getId()));

        UUID draftWordId = UUID.randomUUID();
        String initialDraftData =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "lemma": "draft_incomplete_src",
                  "language_code": "en",
                  "senses": [{"id":"c2000000-0000-0000-0000-000000000091","sense_no":1,"part_of_speech":"NOUN","cefr_level":"A1","meaning_vi":"nháp chưa hoàn chỉnh","examples":[]}]
                }
                """;
        authoringService.createItemWithDraft(
                draftWordId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:draft_inc",
                initialDraftData,
                "v1",
                draftSourceNoAttr.getId());
        ContentRevision draftRev =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(draftWordId).get(0);
        assertThat(draftRev.getStatus()).isEqualTo("DRAFT");

        // 2. EXTERNAL source with attribution and licenseCode succeeds publish
        ContentSource extWithLicenseAndAttr =
                sourceRepository.saveAndFlush(
                        new ContentSource(
                                UUID.randomUUID(),
                                "External Source With License & Attr",
                                "EXTERNAL",
                                "https://ext-license.example.com",
                                "Oxford Authors",
                                "CC-BY-4.0",
                                "https://creativecommons.org/licenses/by/4.0/",
                                "Attributed to Oxford Academic Press",
                                null,
                                "ACTIVE",
                                contentAdminUser.getId()));

        UUID wordExtLicenseId = UUID.randomUUID();
        String wordExtLicenseData =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "lemma": "ext_license_ok",
                  "language_code": "en",
                  "senses": [{"id":"c2000000-0000-0000-0000-000000000092","sense_no":1,"part_of_speech":"NOUN","cefr_level":"B2","meaning_vi":"nguồn ngoài có giấy phép","examples":[]}]
                }
                """;
        authoringService.createItemWithDraft(
                wordExtLicenseId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:ext_lic_ok",
                wordExtLicenseData,
                "v1",
                extWithLicenseAndAttr.getId());
        ContentRevision revExtLic =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(wordExtLicenseId).get(0);
        authoringService.submitForReview(revExtLic.getId(), contentAdminUser.getId());
        authoringService.approve(revExtLic.getId(), contentAdminUser.getId(), "Approved");

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/{id}/validate", revExtLic.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/{id}/publish", revExtLic.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        ContentRevision publishedLic = revisionRepository.findById(revExtLic.getId()).orElseThrow();
        assertThat(publishedLic.getStatus()).isEqualTo("PUBLISHED");
        assertThat(itemRepository.findById(wordExtLicenseId).get().getPublishedRevisionId())
                .isEqualTo(publishedLic.getId());
        List<ContentSourceLink> licLinks =
                sourceLinkRepository.findByIdRevisionId(publishedLic.getId());
        assertThat(licLinks).isNotEmpty();
        assertThat(licLinks.get(0).getSourceSnapshot())
                .contains("Attributed to Oxford Academic Press");

        // 3. EXTERNAL source with attribution and permissionNote (no licenseCode) succeeds publish
        ContentSource extWithNoteAndAttr =
                sourceRepository.saveAndFlush(
                        new ContentSource(
                                UUID.randomUUID(),
                                "External Source With Note & Attr",
                                "EXTERNAL",
                                "https://ext-note.example.com",
                                "Cambridge Authors",
                                null,
                                null,
                                "Attributed to Cambridge Assessment",
                                "Direct written permission granted",
                                "ACTIVE",
                                contentAdminUser.getId()));

        UUID wordExtNoteId = UUID.randomUUID();
        String wordExtNoteData =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "lemma": "ext_note_ok",
                  "language_code": "en",
                  "senses": [{"id":"c2000000-0000-0000-0000-000000000093","sense_no":1,"part_of_speech":"NOUN","cefr_level":"C1","meaning_vi":"nguồn ngoài có ghi chú quyền","examples":[]}]
                }
                """;
        authoringService.createItemWithDraft(
                wordExtNoteId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:ext_note_ok",
                wordExtNoteData,
                "v1",
                extWithNoteAndAttr.getId());
        ContentRevision revExtNote =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(wordExtNoteId).get(0);
        authoringService.submitForReview(revExtNote.getId(), contentAdminUser.getId());
        authoringService.approve(revExtNote.getId(), contentAdminUser.getId(), "Approved");

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/{id}/validate", revExtNote.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/{id}/publish", revExtNote.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        // 4. SELF_AUTHORED source without attribution succeeds publish (attribution not enforced
        // for SELF_AUTHORED)
        ContentSource selfAuthNoAttr =
                sourceRepository.saveAndFlush(
                        new ContentSource(
                                UUID.randomUUID(),
                                "Internal Self-Authored Source",
                                "SELF_AUTHORED",
                                null,
                                "Internal Editorial Team",
                                "EDITORIAL_LICENSE",
                                null,
                                null, // no attribution
                                null,
                                "ACTIVE",
                                contentAdminUser.getId()));

        UUID wordSelfId = UUID.randomUUID();
        String wordSelfData =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "lemma": "self_authored_ok",
                  "language_code": "en",
                  "senses": [{"id":"c2000000-0000-0000-0000-000000000094","sense_no":1,"part_of_speech":"NOUN","cefr_level":"A2","meaning_vi":"tự biên soạn","examples":[]}]
                }
                """;
        authoringService.createItemWithDraft(
                wordSelfId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:self_ok",
                wordSelfData,
                "v1",
                selfAuthNoAttr.getId());
        ContentRevision revSelf =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(wordSelfId).get(0);
        authoringService.submitForReview(revSelf.getId(), contentAdminUser.getId());
        authoringService.approve(revSelf.getId(), contentAdminUser.getId(), "Approved");

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/{id}/validate", revSelf.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/{id}/publish", revSelf.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        ContentRevision publishedSelf = revisionRepository.findById(revSelf.getId()).orElseThrow();
        assertThat(publishedSelf.getStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("C3 / AC16/AC24 - UUID sai định dạng khi update draft trả về 400 không phải 500")
    void invalidUuidInDraftUpdateReturnsBadRequest() throws Exception {
        UUID itemId = UUID.randomUUID();
        authoringService.createItemWithDraft(
                itemId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:bad_uuid",
                "{\"lemma\":\"baduuid\",\"language_code\":\"en\",\"senses\":[]}",
                "v1",
                defaultSource.getId());
        ContentRevision rev =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(itemId).get(0);

        long versionBefore = rev.getVersion();
        String snapshotBefore = rev.getSnapshot();

        // 1. Invalid sense ID
        String badSenseData =
                """
                {
                  "lemma": "baduuid",
                  "language_code": "en",
                  "senses": [{"id":"not-a-valid-uuid","sense_no":1,"part_of_speech":"NOUN","cefr_level":"B1","meaning_vi":"nghĩa","examples":[]}]
                }
                """;
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        versionBefore,
                                                        toMap(badSenseData),
                                                        List.of(defaultSource.getId()),
                                                        "Bad sense UUID"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail").value("Invalid UUID for sense id: not-a-valid-uuid"));

        // 2. Invalid example ID
        String badExampleData =
                """
                {
                  "lemma": "baduuid",
                  "language_code": "en",
                  "senses": [{"id":"c2000000-0000-0000-0000-000000000077","sense_no":1,"part_of_speech":"NOUN","cefr_level":"B1","meaning_vi":"nghĩa","examples":[{"id":"not-an-example-uuid","position":1,"example_en":"Ex","example_vi":"Vd"}]}]
                }
                """;
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        versionBefore,
                                                        toMap(badExampleData),
                                                        List.of(defaultSource.getId()),
                                                        "Bad example UUID"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail")
                                .value("Invalid UUID for example id: not-an-example-uuid"));

        // Verify no mutation
        ContentRevision revAfter = revisionRepository.findById(rev.getId()).orElseThrow();
        assertThat(revAfter.getVersion()).isEqualTo(versionBefore);
        assertThat(revAfter.getSnapshot()).isEqualTo(snapshotBefore);
    }

    @Test
    @DisplayName(
            "C3 / AC16 - validateRevision với member word_sense_id không phải UUID trả về 200"
                    + " valid=false")
    void validateRevisionWithMalformedMemberReturnsValidationErrorsNotInternalError()
            throws Exception {
        UUID deckId = UUID.randomUUID();
        authoringService.createItemWithDraft(
                deckId,
                "DECK",
                contentAdminUser.getId(),
                "admin",
                "deck:malformed_member",
                "{\"title\":\"Malformed Member"
                        + " Deck\",\"members\":[{\"word_sense_id\":\"not-a-uuid-format\"}]}",
                "v1",
                defaultSource.getId());
        ContentRevision rev =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(deckId).get(0);

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/{id}/validate", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[?(@.field == 'members[0].word_sense_id')]").exists())
                .andExpect(
                        jsonPath("$.errors[?(@.field == 'members[0].word_sense_id')].message")
                                .value("Invalid UUID format: not-a-uuid-format"));
    }

    @Test
    @DisplayName(
            "C5 / AC01/AC03 - Không được chuyển sense hoặc example từ Word đã ARCHIVED hoặc"
                    + " historical")
    void cannotStealSenseOrExampleFromArchivedOrHistoricalWord() throws Exception {
        UUID wordAId = UUID.randomUUID();
        String wordAData =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "lemma": "word_a_archived",
                  "language_code": "en",
                  "senses": [
                    {
                      "id": "c2000000-0000-0000-0000-000000000066",
                      "sense_no": 1,
                      "part_of_speech": "NOUN",
                      "cefr_level": "B2",
                      "meaning_vi": "từ A",
                      "examples": [
                        {
                          "id": "c3000000-0000-0000-0000-000000000066",
                          "position": 1,
                          "example_en": "Example A",
                          "example_vi": "Ví dụ A"
                        }
                      ]
                    }
                  ]
                }
                """;
        authoringService.createItemWithDraft(
                wordAId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:a_arch",
                wordAData,
                "v1",
                defaultSource.getId());
        ContentRevision revA =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(wordAId).get(0);
        authoringService.submitForReview(revA.getId(), contentAdminUser.getId());
        authoringService.approve(revA.getId(), contentAdminUser.getId(), "Approved A");
        authoringService.publish(revA.getId(), contentAdminUser.getId());

        // Archive Word A
        authoringService.archiveItem(wordAId, "Archived for test", contentAdminUser.getId());

        // Create Word B draft
        UUID wordBId = UUID.randomUUID();
        String wordBData =
                """
                {
                  "lemma": "word_b",
                  "language_code": "en",
                  "senses": []
                }
                """;
        authoringService.createItemWithDraft(
                wordBId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:b_draft",
                wordBData,
                "v1",
                defaultSource.getId());
        ContentRevision revB =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(wordBId).get(0);

        // 1. Try to steal Sense A from archived Word A into Word B -> 400
        String stealSenseData =
                """
                {
                  "lemma": "word_b",
                  "language_code": "en",
                  "senses": [
                    {
                      "id": "c2000000-0000-0000-0000-000000000066",
                      "sense_no": 1,
                      "part_of_speech": "NOUN",
                      "cefr_level": "B2",
                      "meaning_vi": "từ B ăn cắp sense A",
                      "examples": []
                    }
                  ]
                }
                """;
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", revB.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        revB.getVersion(),
                                                        toMap(stealSenseData),
                                                        List.of(defaultSource.getId()),
                                                        "Stealing sense A"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("A sense cannot move to another word."));

        // 2. Try to steal Example A from archived Word A into Word B -> 400
        String stealExampleData =
                """
                {
                  "lemma": "word_b",
                  "language_code": "en",
                  "senses": [
                    {
                      "id": "c2000000-0000-0000-0000-000000000099",
                      "sense_no": 1,
                      "part_of_speech": "NOUN",
                      "cefr_level": "B2",
                      "meaning_vi": "từ B có sense mới",
                      "examples": [
                        {
                          "id": "c3000000-0000-0000-0000-000000000066",
                          "position": 1,
                          "example_en": "Example A stolen",
                          "example_vi": "Ví dụ A ăn cắp"
                        }
                      ]
                    }
                  ]
                }
                """;
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", revB.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        revB.getVersion(),
                                                        toMap(stealExampleData),
                                                        List.of(defaultSource.getId()),
                                                        "Stealing example A"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("An example cannot move to another word."));
    }

    @Test
    @DisplayName(
            "C5 / AC03/AC21 - pinned_word_revision_id của Deck được bảo toàn khi Word xuất bản v2")
    void deckPinnedRevisionPreservedAcrossWordV2() throws Exception {
        // 1. Publish Word W1 with Sense S1 (version 1)
        UUID wordId = UUID.randomUUID();
        UUID senseId = UUID.fromString("c2000000-0000-0000-0000-000000000055");
        String wordV1Data =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "lemma": "pinword",
                  "language_code": "en",
                  "senses": [{"id":"c2000000-0000-0000-0000-000000000055","sense_no":1,"part_of_speech":"NOUN","cefr_level":"B1","meaning_vi":"nghĩa gốc v1","examples":[]}]
                }
                """;
        authoringService.createItemWithDraft(
                wordId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:pin_w",
                wordV1Data,
                "v1",
                defaultSource.getId());
        UUID wordV1RevId =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(wordId).get(0).getId();
        authoringService.submitForReview(wordV1RevId, contentAdminUser.getId());
        authoringService.approve(wordV1RevId, contentAdminUser.getId(), "Approve Word v1");
        authoringService.publish(wordV1RevId, contentAdminUser.getId());

        // 2. Publish Deck D pinning Word v1
        UUID deckId = UUID.randomUUID();
        authoringService.createItemWithDraft(
                deckId,
                "DECK",
                contentAdminUser.getId(),
                "admin",
                "deck:pin_d",
                "{\"title\":\"Pinned Deck\",\"members\":[{\"word_sense_id\":\"" + senseId + "\"}]}",
                "v1",
                defaultSource.getId());
        UUID deckV1RevId =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(deckId).get(0).getId();
        authoringService.submitForReview(deckV1RevId, contentAdminUser.getId());
        authoringService.approve(deckV1RevId, contentAdminUser.getId(), "Approve Deck v1");
        authoringService.publish(deckV1RevId, contentAdminUser.getId());

        // Check Deck v1 dependency in DB points to wordV1RevId
        var depsBefore = dependencyRepository.findByIdParentRevisionId(deckV1RevId);
        assertThat(depsBefore).hasSize(1);
        assertThat(depsBefore.get(0).getChildRevisionId()).isEqualTo(wordV1RevId);

        // 3. Word creates v2 and publishes v2
        var cloneWordAction =
                mockMvc.perform(
                                post("/api/v1/admin/content/items/{id}/revisions", wordId)
                                        .with(csrf())
                                        .with(
                                                SecurityMockMvcRequestPostProcessors.user(
                                                        adminPrincipal()))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        new CreateRevisionFromPublishedRequest(
                                                                wordV1RevId,
                                                                null,
                                                                "Word v2 clone"))))
                        .andExpect(status().isCreated())
                        .andReturn();
        UUID wordV2RevId =
                UUID.fromString(
                        com.jayway.jsonpath.JsonPath.read(
                                cloneWordAction.getResponse().getContentAsString(),
                                "$.revisionId"));
        authoringService.submitForReview(wordV2RevId, contentAdminUser.getId());
        authoringService.approve(wordV2RevId, contentAdminUser.getId(), "Approve Word v2");
        authoringService.publish(wordV2RevId, contentAdminUser.getId());

        // Word's current published revision is now wordV2RevId
        assertThat(itemRepository.findById(wordId).get().getPublishedRevisionId())
                .isEqualTo(wordV2RevId);

        // 4. Deck D clones v1 to create v2 draft
        var cloneDeckAction =
                mockMvc.perform(
                                post("/api/v1/admin/content/items/{id}/revisions", deckId)
                                        .with(csrf())
                                        .with(
                                                SecurityMockMvcRequestPostProcessors.user(
                                                        adminPrincipal()))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        new CreateRevisionFromPublishedRequest(
                                                                deckV1RevId,
                                                                null,
                                                                "Deck v2 clone"))))
                        .andExpect(status().isCreated())
                        .andReturn();
        UUID deckV2RevId =
                UUID.fromString(
                        com.jayway.jsonpath.JsonPath.read(
                                cloneDeckAction.getResponse().getContentAsString(),
                                "$.revisionId"));
        ContentRevision deckV2Rev = revisionRepository.findById(deckV2RevId).orElseThrow();

        // 5. Client edits Deck v2 title, retaining member with pinned_word_revision_id =
        // wordV1RevId
        String updateDeckData =
                "{\"title\":\"Updated Deck Title\",\"members\":[{\"word_sense_id\":\""
                        + senseId
                        + "\",\"pinned_word_revision_id\":\""
                        + wordV1RevId
                        + "\"}]}";
        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", deckV2RevId)
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        deckV2Rev.getVersion(),
                                                        toMap(updateDeckData),
                                                        List.of(defaultSource.getId()),
                                                        "Updated title preserving pin"))))
                .andExpect(status().isOk());

        // Verify snapshot in DB retains pinned_word_revision_id = wordV1RevId
        ContentRevision deckV2After = revisionRepository.findById(deckV2RevId).orElseThrow();
        JsonNode data = objectMapper.readTree(deckV2After.getSnapshot()).path("data");
        assertThat(data.path("title").asText()).isEqualTo("Updated Deck Title");
        assertThat(data.path("members").get(0).path("pinned_word_revision_id").asText())
                .isEqualTo(wordV1RevId.toString());

        // Publish Deck v2 and verify dependency repository in DB
        authoringService.submitForReview(deckV2RevId, contentAdminUser.getId());
        authoringService.approve(deckV2RevId, contentAdminUser.getId(), "Approve Deck v2");
        authoringService.publish(deckV2RevId, contentAdminUser.getId());

        var depsV2 = dependencyRepository.findByIdParentRevisionId(deckV2RevId);
        assertThat(depsV2).hasSize(1);
        assertThat(depsV2.get(0).getChildRevisionId()).isEqualTo(wordV1RevId);
    }

    @Test
    @DisplayName("T2 - Cạnh tranh cập nhật draft cùng version: đúng một thành công và một 409")
    void concurrentDraftUpdatesHonorsCasLocking() throws Exception {
        UUID itemId = UUID.randomUUID();
        String initialData =
                """
                {
                  "lemma": "concurrent",
                  "language_code": "en",
                  "senses": [
                    {
                      "id": "c2000000-0000-0000-0000-000000000099",
                      "sense_no": 1,
                      "part_of_speech": "VERB",
                      "cefr_level": "B2",
                      "meaning_vi": "đồng thời",
                      "examples": []
                    }
                  ]
                }
                """;
        authoringService.createItemWithDraft(
                itemId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:concurrent",
                initialData,
                "v1",
                defaultSource.getId());
        ContentRevision rev =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(itemId).get(0);
        long version = rev.getVersion();

        String payload1 =
                """
                {
                  "lemma": "concurrent",
                  "language_code": "en",
                  "senses": [
                    {
                      "id": "c2000000-0000-0000-0000-000000000099",
                      "sense_no": 1,
                      "part_of_speech": "VERB",
                      "cefr_level": "B2",
                      "meaning_vi": "đồng thời 1",
                      "examples": []
                    }
                  ]
                }
                """;

        String payload2 =
                """
                {
                  "lemma": "concurrent",
                  "language_code": "en",
                  "senses": [
                    {
                      "id": "c2000000-0000-0000-0000-000000000099",
                      "sense_no": 1,
                      "part_of_speech": "VERB",
                      "cefr_level": "B2",
                      "meaning_vi": "đồng thời 2",
                      "examples": []
                    }
                  ]
                }
                """;

        org.springframework.test.context.transaction.TestTransaction.flagForCommit();
        org.springframework.test.context.transaction.TestTransaction.end();

        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var task1 =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return mockMvc.perform(
                                                put(
                                                                "/api/v1/admin/content/revisions/{id}",
                                                                rev.getId())
                                                        .with(csrf())
                                                        .with(
                                                                SecurityMockMvcRequestPostProcessors
                                                                        .user(adminPrincipal()))
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                objectMapper.writeValueAsString(
                                                                        new UpdateRevisionDraftRequest(
                                                                                version,
                                                                                toMap(payload1),
                                                                                List.of(
                                                                                        defaultSource
                                                                                                .getId()),
                                                                                "Update 1"))))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });

            var task2 =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return mockMvc.perform(
                                                put(
                                                                "/api/v1/admin/content/revisions/{id}",
                                                                rev.getId())
                                                        .with(csrf())
                                                        .with(
                                                                SecurityMockMvcRequestPostProcessors
                                                                        .user(adminPrincipal()))
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                objectMapper.writeValueAsString(
                                                                        new UpdateRevisionDraftRequest(
                                                                                version,
                                                                                toMap(payload2),
                                                                                List.of(
                                                                                        defaultSource
                                                                                                .getId()),
                                                                                "Update 2"))))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });

            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int status1 = task1.get(20, java.util.concurrent.TimeUnit.SECONDS);
            int status2 = task2.get(20, java.util.concurrent.TimeUnit.SECONDS);

            List<Integer> statuses = List.of(status1, status2);
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            start.countDown();
            executor.shutdownNow();
            org.springframework.test.context.transaction.TestTransaction.start();
        }
    }

    @Test
    @DisplayName(
            "T2 - Cạnh tranh tạo revision từ Published: chỉ đúng một revision mở được tạo, request kia 409")
    void concurrentRevisionClonesAllowsOnlyOneOpenDraft() throws Exception {
        UUID itemId = UUID.randomUUID();
        String wordData =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "lemma": "concurrentclone",
                  "language_code": "en",
                  "senses": [
                    {
                      "id": "c2000000-0000-0000-0000-000000000098",
                      "sense_no": 1,
                      "part_of_speech": "NOUN",
                      "cefr_level": "B2",
                      "meaning_vi": "đồng thời clone",
                      "examples": []
                    }
                  ]
                }
                """;
        authoringService.createItemWithDraft(
                itemId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:cclone",
                wordData,
                "v1",
                defaultSource.getId());
        ContentRevision v1 =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(itemId).get(0);
        authoringService.submitForReview(v1.getId(), contentAdminUser.getId());
        authoringService.approve(v1.getId(), contentAdminUser.getId(), "OK");
        authoringService.publish(v1.getId(), contentAdminUser.getId());

        org.springframework.test.context.transaction.TestTransaction.flagForCommit();
        org.springframework.test.context.transaction.TestTransaction.end();

        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var task1 =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return mockMvc.perform(
                                                post(
                                                                "/api/v1/admin/content/items/{id}/revisions",
                                                                itemId)
                                                        .with(csrf())
                                                        .with(
                                                                SecurityMockMvcRequestPostProcessors
                                                                        .user(adminPrincipal()))
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                objectMapper.writeValueAsString(
                                                                        new CreateRevisionFromPublishedRequest(
                                                                                v1.getId(),
                                                                                null,
                                                                                "Clone attempt 1"))))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });

            var task2 =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return mockMvc.perform(
                                                post(
                                                                "/api/v1/admin/content/items/{id}/revisions",
                                                                itemId)
                                                        .with(csrf())
                                                        .with(
                                                                SecurityMockMvcRequestPostProcessors
                                                                        .user(adminPrincipal()))
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                objectMapper.writeValueAsString(
                                                                        new CreateRevisionFromPublishedRequest(
                                                                                v1.getId(),
                                                                                null,
                                                                                "Clone attempt 2"))))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });

            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int status1 = task1.get(20, java.util.concurrent.TimeUnit.SECONDS);
            int status2 = task2.get(20, java.util.concurrent.TimeUnit.SECONDS);

            List<Integer> statuses = List.of(status1, status2);
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);

            List<ContentRevision> allRevs =
                    revisionRepository.findByContentItemIdOrderByVersionNoDesc(itemId);
            assertThat(allRevs).hasSize(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
            org.springframework.test.context.transaction.TestTransaction.start();
        }
    }

    @Test
    @DisplayName("R1 / C5 / AC01 - Cấm chuyển example sang sense khác trong cùng một Word")
    void cannotMoveExampleToAnotherSenseInSameWord() throws Exception {
        UUID wordId = UUID.randomUUID();
        UUID sense1Id = UUID.fromString("c2000000-0000-0000-0000-000000000091");
        UUID sense2Id = UUID.fromString("c2000000-0000-0000-0000-000000000092");
        UUID exampleId = UUID.fromString("c3000000-0000-0000-0000-000000000091");

        String initialData =
                String.format(
                        """
                        {
                          "lemma": "cross_sense_example",
                          "language_code": "en",
                          "senses": [
                            {
                              "id": "%s",
                              "sense_no": 1,
                              "part_of_speech": "NOUN",
                              "cefr_level": "A1",
                              "meaning_vi": "nghĩa 1",
                              "examples": [
                                {
                                  "id": "%s",
                                  "position": 1,
                                  "example_en": "Example in sense 1",
                                  "example_vi": "Ví dụ ở nghĩa 1"
                                }
                              ]
                            },
                            {
                              "id": "%s",
                              "sense_no": 2,
                              "part_of_speech": "VERB",
                              "cefr_level": "A2",
                              "meaning_vi": "nghĩa 2",
                              "examples": []
                            }
                          ]
                        }
                        """,
                        sense1Id, exampleId, sense2Id);

        authoringService.createItemWithDraft(
                wordId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:cross_sense_example",
                initialData,
                "v1",
                defaultSource.getId());

        ContentRevision rev =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(wordId).get(0);
        String snapshotBefore = rev.getSnapshot();

        // Cố gắng chuyển exampleId từ sense 1 sang sense 2
        Map<String, Object> movedExample =
                Map.of(
                        "id",
                        exampleId.toString(),
                        "position",
                        1,
                        "example_en",
                        "Example moved to sense 2",
                        "example_vi",
                        "Ví dụ chuyển sang nghĩa 2");

        Map<String, Object> updatedSense1 =
                Map.of(
                        "id",
                        sense1Id.toString(),
                        "sense_no",
                        1,
                        "part_of_speech",
                        "NOUN",
                        "cefr_level",
                        "A1",
                        "meaning_vi",
                        "nghĩa 1",
                        "examples",
                        List.of());

        Map<String, Object> updatedSense2 =
                Map.of(
                        "id",
                        sense2Id.toString(),
                        "sense_no",
                        2,
                        "part_of_speech",
                        "VERB",
                        "cefr_level",
                        "A2",
                        "meaning_vi",
                        "nghĩa 2",
                        "examples",
                        List.of(movedExample));

        Map<String, Object> updateData =
                Map.of(
                        "lemma", "cross_sense_example",
                        "language_code", "en",
                        "senses", List.of(updatedSense1, updatedSense2));

        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        rev.getVersion(),
                                                        updateData,
                                                        List.of(defaultSource.getId()),
                                                        "Move example between senses"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("An example cannot move to another sense."));

        ContentRevision revAfter = revisionRepository.findById(rev.getId()).orElseThrow();
        assertThat(revAfter.getSnapshot()).isEqualTo(snapshotBefore);
    }

    @Test
    @DisplayName("R2 / C2 / AC13/AC29 - URL parser kiểm tra host hợp lệ và status ACTIVE|ARCHIVED")
    void sourceCreateAndUpdateRejectsInvalidUrlsWithoutHostAndInvalidStatus() throws Exception {
        long auditBefore = auditLogRepository.count();

        // 1. Create source with missing host in sourceUrl: https:///missing-host -> 400
        mockMvc.perform(
                        post("/api/v1/admin/content/sources")
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateContentSourceRequest(
                                                        "Missing Host Source",
                                                        "EXTERNAL",
                                                        "https:///missing-host",
                                                        "Author",
                                                        "CC-BY",
                                                        null,
                                                        "Attr",
                                                        "Note"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[?(@.field == 'sourceUrl')]").exists());

        // 2. Create source with missing host in licenseUrl: https://? -> 400
        mockMvc.perform(
                        post("/api/v1/admin/content/sources")
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateContentSourceRequest(
                                                        "Question Host Source",
                                                        "EXTERNAL",
                                                        "https://example.com",
                                                        "Author",
                                                        "CC-BY",
                                                        "https://?",
                                                        "Attr",
                                                        "Note"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[?(@.field == 'licenseUrl')]").exists());

        // 3. Create source with missing host in sourceUrl: https://#fragment -> 400
        mockMvc.perform(
                        post("/api/v1/admin/content/sources")
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateContentSourceRequest(
                                                        "Fragment Host Source",
                                                        "EXTERNAL",
                                                        "https://#fragment",
                                                        "Author",
                                                        "CC-BY",
                                                        null,
                                                        "Attr",
                                                        "Note"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[?(@.field == 'sourceUrl')]").exists());

        // Create a valid source to test update
        ContentSource validSource =
                sourceRepository.saveAndFlush(
                        new ContentSource(
                                UUID.randomUUID(),
                                "Updatable Source",
                                "EXTERNAL",
                                "https://valid-source.com",
                                "Author",
                                "CC-BY",
                                "https://valid-license.com",
                                "Attribution",
                                "Note",
                                "ACTIVE",
                                contentAdminUser.getId()));

        long auditAfterSourceCreation = auditLogRepository.count();

        // 4. Update source with missing host in sourceUrl -> 400
        mockMvc.perform(
                        put("/api/v1/admin/content/sources/{id}", validSource.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateContentSourceRequest(
                                                        "Updatable Source",
                                                        "EXTERNAL",
                                                        "https:///missing-host",
                                                        "Author",
                                                        "CC-BY",
                                                        "https://valid-license.com",
                                                        "Attribution",
                                                        "Note",
                                                        "ACTIVE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[?(@.field == 'sourceUrl')]").exists());

        // 5. Update source with INACTIVE status -> 400 (only ACTIVE and ARCHIVED allowed)
        mockMvc.perform(
                        put("/api/v1/admin/content/sources/{id}", validSource.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateContentSourceRequest(
                                                        "Updatable Source",
                                                        "EXTERNAL",
                                                        "https://valid-source.com",
                                                        "Author",
                                                        "CC-BY",
                                                        "https://valid-license.com",
                                                        "Attribution",
                                                        "Note",
                                                        "INACTIVE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[?(@.field == 'status')]").exists());

        // 6. Update source with ARCHIVED status and valid URLs -> 200 OK
        mockMvc.perform(
                        put("/api/v1/admin/content/sources/{id}", validSource.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateContentSourceRequest(
                                                        "Updatable Source",
                                                        "EXTERNAL",
                                                        "https://valid-source.com/updated",
                                                        "Author",
                                                        "CC-BY",
                                                        "https://valid-license.com/updated",
                                                        "Attribution",
                                                        "Note",
                                                        "ARCHIVED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        // Verify DB state and audit count: exactly 1 audit log generated for the 1 successful
        // update
        ContentSource updated = sourceRepository.findById(validSource.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("ARCHIVED");
        assertThat(updated.getSourceUrl()).isEqualTo("https://valid-source.com/updated");
        assertThat(auditLogRepository.count()).isEqualTo(auditAfterSourceCreation + 1);
    }

    @Test
    @DisplayName("R3 / C4 / AC16/AC24 - updateDraft từ chối unknown fields và envelope lồng data")
    void updateDraftRejectsUnknownFieldsAndEnvelopeNesting() throws Exception {
        UUID wordId = UUID.randomUUID();
        UUID senseId = UUID.fromString("c2000000-0000-0000-0000-000000000081");
        UUID exampleId = UUID.fromString("c3000000-0000-0000-0000-000000000081");

        String initialData =
                String.format(
                        """
                        {
                          "lemma": "test_known_fields",
                          "language_code": "en",
                          "senses": [
                            {
                              "id": "%s",
                              "sense_no": 1,
                              "part_of_speech": "NOUN",
                              "cefr_level": "A1",
                              "meaning_vi": "nghĩa thử",
                              "examples": [
                                {
                                  "id": "%s",
                                  "position": 1,
                                  "example_en": "Example",
                                  "example_vi": "Ví dụ"
                                }
                              ]
                            }
                          ]
                        }
                        """,
                        senseId, exampleId);

        authoringService.createItemWithDraft(
                wordId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:test_known",
                initialData,
                "v1",
                defaultSource.getId());

        ContentRevision rev =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(wordId).get(0);
        String snapshotBefore = rev.getSnapshot();

        // 1. Unknown top-level field in Word data -> 400
        Map<String, Object> dataWithUnknownField =
                Map.of(
                        "lemma", "test_known_fields",
                        "language_code", "en",
                        "bogus_field", "evil_payload");

        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        rev.getVersion(),
                                                        dataWithUnknownField,
                                                        List.of(defaultSource.getId()),
                                                        "Unknown field test"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Unsupported snapshot field: bogus_field"));

        // 2. Nested envelope "data" inside data to hide fields -> 400
        Map<String, Object> nestedDataEnvelope =
                Map.of("data", Map.of("lemma", "sneaky_word", "bogus", 123));

        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        rev.getVersion(),
                                                        nestedDataEnvelope,
                                                        List.of(defaultSource.getId()),
                                                        "Nested data envelope test"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Unsupported snapshot field: data"));

        // 3. Unknown field in sense object -> 400
        Map<String, Object> senseWithUnknownField =
                Map.of(
                        "id", senseId.toString(),
                        "sense_no", 1,
                        "part_of_speech", "NOUN",
                        "cefr_level", "A1",
                        "meaning_vi", "nghĩa",
                        "bogus_sense_prop", "not_allowed",
                        "examples", List.of());

        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        rev.getVersion(),
                                                        Map.of(
                                                                "lemma",
                                                                "test_known_fields",
                                                                "senses",
                                                                List.of(senseWithUnknownField)),
                                                        List.of(defaultSource.getId()),
                                                        "Unknown sense field test"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail").value("Unsupported snapshot field: bogus_sense_prop"));

        // 4. Unknown field in example object -> 400
        Map<String, Object> exampleWithUnknownField =
                Map.of(
                        "id",
                        exampleId.toString(),
                        "position",
                        1,
                        "example_en",
                        "test",
                        "example_vi",
                        "test",
                        "bogus_example_prop",
                        999);

        Map<String, Object> senseWithBadExample =
                Map.of(
                        "id",
                        senseId.toString(),
                        "sense_no",
                        1,
                        "part_of_speech",
                        "NOUN",
                        "cefr_level",
                        "A1",
                        "meaning_vi",
                        "nghĩa",
                        "examples",
                        List.of(exampleWithUnknownField));

        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        rev.getVersion(),
                                                        Map.of(
                                                                "lemma",
                                                                "test_known_fields",
                                                                "senses",
                                                                List.of(senseWithBadExample)),
                                                        List.of(defaultSource.getId()),
                                                        "Unknown example field test"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail")
                                .value("Unsupported snapshot field: bogus_example_prop"));

        // Verify snapshot not mutated after all invalid attempts
        ContentRevision revAfterFails = revisionRepository.findById(rev.getId()).orElseThrow();
        assertThat(revAfterFails.getSnapshot()).isEqualTo(snapshotBefore);

        // 5. Partial draft save is ALLOWED (missing senses/ipa/language_code) -> 200 OK
        Map<String, Object> validPartialData = Map.of("lemma", "valid_partial_lemma");

        mockMvc.perform(
                        put("/api/v1/admin/content/revisions/{id}", rev.getId())
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateRevisionDraftRequest(
                                                        rev.getVersion(),
                                                        validPartialData,
                                                        List.of(defaultSource.getId()),
                                                        "Valid partial draft save"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").isNumber());

        ContentRevision revAfterSuccess = revisionRepository.findById(rev.getId()).orElseThrow();
        assertThat(revAfterSuccess.getSnapshot()).contains("valid_partial_lemma");
    }

    @Test
    @DisplayName("AC20/AC24 - Unpublish yêu cầu xác thực, quyền Content Admin và CSRF token")
    void unpublishRequiresAuthenticationAndAuthorRoleAndCsrf() throws Exception {
        UUID randomId = UUID.randomUUID();

        // 1. Missing authentication -> 401 Unauthorized
        mockMvc.perform(post("/api/v1/admin/content/items/{id}/unpublish", randomId).with(csrf()))
                .andExpect(status().isUnauthorized());

        // 2. Authenticated as LEARNER -> 403 Forbidden
        mockMvc.perform(
                        post("/api/v1/admin/content/items/{id}/unpublish", randomId)
                                .with(csrf())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                learnerPrincipal())))
                .andExpect(status().isForbidden());

        // 3. Missing CSRF token -> 403 Forbidden
        mockMvc.perform(
                        post("/api/v1/admin/content/items/{id}/unpublish", randomId)
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName(
            "AC20/AC24 - Unpublish trả về 404 nếu không tìm thấy item, 409 nếu item chưa từng được xuất bản")
    void unpublishFailsOnUnknownItemOrUnpublishedDraft() throws Exception {
        UUID unknownId = UUID.randomUUID();

        // 1. Unknown itemId -> 404 Not Found
        mockMvc.perform(
                        post("/api/v1/admin/content/items/{id}/unpublish", unknownId)
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        // 2. Draft item (never published) -> 409 Conflict
        UUID draftWordId = UUID.randomUUID();
        String draftData =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "lemma": "unpublish_draft_only",
                  "language_code": "en",
                  "senses": [{"id":"c2000000-0000-0000-0000-000000000055","sense_no":1,"part_of_speech":"NOUN","cefr_level":"A1","meaning_vi":"nghĩa nháp","examples":[]}]
                }
                """;
        authoringService.createItemWithDraft(
                draftWordId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:unpub_draft",
                draftData,
                "v1",
                defaultSource.getId());

        mockMvc.perform(
                        post("/api/v1/admin/content/items/{id}/unpublish", draftWordId)
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(
                        jsonPath("$.detail").value("Only published content can be unpublished."));

        // 3. Submit for review (IN_REVIEW) -> still 409 Conflict
        ContentRevision draftRev =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(draftWordId).get(0);
        authoringService.submitForReview(draftRev.getId(), contentAdminUser.getId());

        mockMvc.perform(
                        post("/api/v1/admin/content/items/{id}/unpublish", draftWordId)
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 4. Approved revision (APPROVED, not yet PUBLISHED) -> still 409 Conflict
        authoringService.approve(draftRev.getId(), contentAdminUser.getId(), "Approved");

        mockMvc.perform(
                        post("/api/v1/admin/content/items/{id}/unpublish", draftWordId)
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName(
            "AC20/AC24/AC27 - Unpublish nội dung đã xuất bản: giữ lịch sử, không cấp mới, lũy thừa không gia hạn và archive tiếp tục thu hồi")
    void unpublishPublishedItemPreservesHistoryExcludesFromCatalogAndIsIdempotent()
            throws Exception {
        // 1. Create and publish a Word
        UUID wordId = UUID.randomUUID();
        String wordData =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "lemma": "unpublish_published",
                  "language_code": "en",
                  "senses": [{"id":"c2000000-0000-0000-0000-000000000056","sense_no":1,"part_of_speech":"NOUN","cefr_level":"B2","meaning_vi":"nghĩa xuất bản","examples":[]}]
                }
                """;
        authoringService.createItemWithDraft(
                wordId,
                "WORD",
                contentAdminUser.getId(),
                "admin",
                "word:unpub_pub",
                wordData,
                "v1",
                defaultSource.getId());
        ContentRevision rev =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(wordId).get(0);
        authoringService.submitForReview(rev.getId(), contentAdminUser.getId());
        authoringService.approve(rev.getId(), contentAdminUser.getId(), "Approved");
        authoringService.publish(rev.getId(), contentAdminUser.getId());

        ContentItem itemBefore = itemRepository.findById(wordId).orElseThrow();
        assertThat(itemBefore.getPublishedRevisionId()).isEqualTo(rev.getId());
        assertThat(itemBefore.isArchived()).isFalse();
        assertThat(itemBefore.getAccessRevokedAt()).isNull();
        assertThat(wordRepository.findById(wordId)).isPresent();

        // Verify present in catalog view before unpublish
        Integer catalogCountBefore =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM catalog_visible_items WHERE id = ?",
                        Integer.class,
                        wordId);
        assertThat(catalogCountBefore).isEqualTo(1);

        // 2. Perform unpublish
        mockMvc.perform(
                        post("/api/v1/admin/content/items/{id}/unpublish", wordId)
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Outdated syllabus\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Nội dung đã được ngừng xuất bản."));

        // 3. Verify DB state: is_archived = true, accessRevokedAt is null, publishedRevisionId
        // unchanged, projection intact
        ContentItem itemUnpublished = itemRepository.findById(wordId).orElseThrow();
        assertThat(itemUnpublished.isArchived()).isTrue();
        assertThat(itemUnpublished.getArchivedAt()).isNotNull();
        assertThat(itemUnpublished.getAccessRevokedAt()).isNull(); // Access NOT revoked
        assertThat(itemUnpublished.getAccessRevokeReason()).isNull();
        assertThat(itemUnpublished.getPublishedRevisionId())
                .isEqualTo(rev.getId()); // Pointer intact
        assertThat(wordRepository.findById(wordId)).isPresent(); // Projection intact

        List<ContentSourceLink> links = sourceLinkRepository.findByIdRevisionId(rev.getId());
        assertThat(links).isNotEmpty();
        assertThat(links.get(0).getSourceSnapshot()).isNotNull(); // Snapshot intact

        Integer unpublishAuditCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_logs WHERE entity_id = ? AND action = 'CONTENT_UNPUBLISHED'",
                        Integer.class,
                        wordId);
        assertThat(unpublishAuditCount).isEqualTo(1);

        // 4. Verify catalog excludes unpublished item (new catalog/selection cannot see it)
        Integer catalogCountAfter =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM catalog_visible_items WHERE id = ?",
                        Integer.class,
                        wordId);
        assertThat(catalogCountAfter).isEqualTo(0);

        // 5. Repeat unpublish call is idempotent (no renewed archive timestamp, no extra audit log)
        Instant firstArchivedAt = itemUnpublished.getArchivedAt();
        mockMvc.perform(
                        post("/api/v1/admin/content/items/{id}/unpublish", wordId)
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Repeat call\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Nội dung đã được ngừng xuất bản."));

        ContentItem itemAfterRepeat = itemRepository.findById(wordId).orElseThrow();
        assertThat(itemAfterRepeat.getArchivedAt()).isEqualTo(firstArchivedAt);
        assertThat(itemAfterRepeat.getAccessRevokedAt()).isNull();

        Integer repeatAuditCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_logs WHERE entity_id = ? AND action = 'CONTENT_UNPUBLISHED'",
                        Integer.class,
                        wordId);
        assertThat(repeatAuditCount).isEqualTo(1); // Still exactly 1 audit log

        // 6. Calling archive afterwards revokes access
        mockMvc.perform(
                        post("/api/v1/admin/content/items/{id}/archive", wordId)
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(adminPrincipal()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Copyright takedown after unpublish\"}"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.message")
                                .value("Nội dung đã được chuyển sang trạng thái lưu trữ."));

        ContentItem itemAfterArchive = itemRepository.findById(wordId).orElseThrow();
        assertThat(itemAfterArchive.isArchived()).isTrue();
        assertThat(itemAfterArchive.getAccessRevokedAt()).isNotNull(); // Now revoked!
        assertThat(itemAfterArchive.getAccessRevokeReason())
                .isEqualTo("Copyright takedown after unpublish");

        Integer archiveAuditCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_logs WHERE entity_id = ? AND action = 'CONTENT_ARCHIVED'",
                        Integer.class,
                        wordId);
        assertThat(archiveAuditCount).isEqualTo(1);
    }
}
