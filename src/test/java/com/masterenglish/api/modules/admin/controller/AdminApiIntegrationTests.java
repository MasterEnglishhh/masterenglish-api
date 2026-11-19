package com.masterenglish.api.modules.admin.controller;

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
import com.masterenglish.api.modules.admin.dto.request.CreateWordDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.LockUserRequest;
import com.masterenglish.api.modules.admin.dto.request.UpdateFeatureFlagRequest;
import com.masterenglish.api.modules.admin.dto.request.UpdateRolesRequest;
import com.masterenglish.api.modules.admin.repository.FeatureFlagRepository;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.service.ContentAdminService;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.RoleRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@org.junit.jupiter.api.condition.EnabledIf("isDockerAvailable")
class AdminApiIntegrationTests {

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
    @Autowired private FeatureFlagRepository featureFlags;
    @Autowired private ContentRevisionRepository revisions;
    @MockitoSpyBean private ContentAdminService content;

    private MockMvc mockMvc;
    private User systemAdminUser;
    private User contentAdminUser;
    private User learnerUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Role adminRole = roleRepository.findByCode(RoleCode.SYSTEM_ADMIN).orElseThrow();
        Role contentRole = roleRepository.findByCode(RoleCode.CONTENT_ADMIN).orElseThrow();
        Role learnerRole = roleRepository.findByCode(RoleCode.LEARNER).orElseThrow();

        systemAdminUser = new User("sysadmin@test.com", "hash", "System", "Admin");
        systemAdminUser.addRole(adminRole);
        systemAdminUser.markEmailVerified(Instant.now());
        systemAdminUser = userRepository.saveAndFlush(systemAdminUser);

        contentAdminUser = new User("contentadmin@test.com", "hash", "Content", "Admin");
        contentAdminUser.addRole(contentRole);
        contentAdminUser.markEmailVerified(Instant.now());
        contentAdminUser = userRepository.saveAndFlush(contentAdminUser);

        learnerUser = new User("learner@test.com", "hash", "Learner", "User");
        learnerUser.addRole(learnerRole);
        learnerUser.markEmailVerified(Instant.now());
        learnerUser = userRepository.saveAndFlush(learnerUser);
    }

    @Test
    @DisplayName("Admin Overview - Thẻ KPI và Activities yêu cầu quyền Admin")
    void overview_endpoints_requireAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/overview/kpis")).andExpect(status().isUnauthorized());

        mockMvc.perform(
                        get("/api/v1/admin/overview/kpis")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learnerUser))))
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        get("/api/v1/admin/overview/kpis")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(systemAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].changePercent").doesNotExist())
                .andExpect(jsonPath("$[1].changePercent").doesNotExist())
                .andExpect(jsonPath("$[3].value").value("Chưa có dữ liệu"))
                .andExpect(jsonPath("$[3].changePercent").doesNotExist());

        mockMvc.perform(
                        get("/api/v1/admin/overview/activities")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(contentAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Admin Users - SYSTEM_ADMIN có thể xem danh sách, cập nhật role và khóa/mở khóa")
    void userManagement_crud_success() throws Exception {
        // CONTENT_ADMIN cannot access user management
        mockMvc.perform(
                        get("/api/v1/admin/users")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(contentAdminUser))))
                .andExpect(status().isForbidden());

        // SYSTEM_ADMIN lists users
        mockMvc.perform(
                        get("/api/v1/admin/users")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(systemAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.totalItems").isNumber());

        // Update roles
        UpdateRolesRequest rolesRequest =
                new UpdateRolesRequest(List.of(RoleCode.LEARNER, RoleCode.CONTENT_ADMIN));
        mockMvc.perform(
                        put("/api/v1/admin/users/" + learnerUser.getId() + "/roles")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(rolesRequest))
                                .with(csrf())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(systemAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").isArray());

        int authVersion =
                userRepository.findById(learnerUser.getId()).orElseThrow().getAuthVersion();
        mockMvc.perform(
                        put("/api/v1/admin/users/{id}/roles", learnerUser.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(rolesRequest))
                                .with(csrf())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(systemAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authVersion").value(authVersion + 1));

        // Lock user
        LockUserRequest lockRequest =
                new LockUserRequest("Vi phạm quy tắc", Instant.now().plusSeconds(86400));
        mockMvc.perform(
                        post("/api/v1/admin/users/" + learnerUser.getId() + "/lock")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(lockRequest))
                                .with(csrf())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(systemAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"));

        // Unlock user
        mockMvc.perform(
                        post("/api/v1/admin/users/" + learnerUser.getId() + "/unlock")
                                .with(csrf())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(systemAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("Admin Content - CONTENT_ADMIN có thể tạo nguồn và tạo bản thảo từ vựng")
    void content_crud_success() throws Exception {
        // Create Content Source
        CreateContentSourceRequest sourceReq =
                new CreateContentSourceRequest(
                        "Oxford Learner's Dictionary",
                        "EXTERNAL",
                        "https://oxford.com",
                        "Oxford",
                        "CC-BY",
                        null,
                        "Attribution Oxford",
                        null);

        mockMvc.perform(
                        post("/api/v1/admin/content/sources")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(sourceReq))
                                .with(csrf())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(contentAdminUser))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.title").value("Oxford Learner's Dictionary"));

        // Create Word Draft
        CreateWordDraftRequest wordReq =
                new CreateWordDraftRequest(
                        "resilience",
                        "NOUN",
                        "B2",
                        "Khả năng phục hồi",
                        "The capacity to recover quickly from difficulties",
                        "/rɪˈzɪliəns/",
                        List.of("Courage and resilience are essential."),
                        List.of("Lòng dũng cảm và sự kiên cường là cần thiết."),
                        null,
                        "Thêm từ vựng mới");

        mockMvc.perform(
                        post("/api/v1/admin/content/words")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(wordReq))
                                .with(csrf())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(contentAdminUser))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("resilience"))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // List Content Items
        mockMvc.perform(
                        get("/api/v1/admin/content/items")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(contentAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
        var principal =
                SecurityMockMvcRequestPostProcessors.user(UserPrincipal.create(contentAdminUser));
        mockMvc.perform(
                        get("/api/v1/admin/content/items")
                                .param("search", "RESILIENCE")
                                .param("status", "DRAFT")
                                .param("size", "1")
                                .with(principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].title").value("resilience"));
        mockMvc.perform(
                        get("/api/v1/admin/content/items")
                                .param("search", "RESILIENCE")
                                .param("status", "PUBLISHED")
                                .with(principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
        mockMvc.perform(
                        get("/api/v1/admin/content/items")
                                .param("search", "no matching fixture")
                                .with(principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    private JsonNode postJson(String url, Object body, RequestPostProcessor principal, int status)
            throws Exception {
        String json =
                mockMvc.perform(
                                post(url)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(body))
                                        .with(csrf())
                                        .with(principal))
                        .andExpect(status().is(status))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.isBlank() ? objectMapper.nullNode() : objectMapper.readTree(json);
    }

    private Map<String, Object> wordBody(String lemma, UUID sourceId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("lemma", lemma);
        body.put("partOfSpeech", "NOUN");
        body.put("cefrLevel", "B1");
        body.put("meaningVi", "nghĩa thử");
        body.put("definitionEn", "a test word");
        body.put("examplesEn", List.of("A test sentence."));
        body.put("examplesVi", List.of("Một câu thử."));
        if (sourceId != null) body.put("sourceId", sourceId);
        return body;
    }

    @Test
    @DisplayName(
            "Admin Content - danh sách nguồn chạy được khi không có và khi có từ khóa tìm kiếm")
    void listSources_withAndWithoutSearch_returnsOk() throws Exception {
        var admin =
                SecurityMockMvcRequestPostProcessors.user(UserPrincipal.create(contentAdminUser));
        postJson(
                "/api/v1/admin/content/sources",
                Map.of(
                        "title",
                        "Nguồn tìm kiếm",
                        "sourceType",
                        "EXTERNAL",
                        "licenseCode",
                        "CC-BY",
                        "attribution",
                        "a"),
                admin,
                201);
        mockMvc.perform(get("/api/v1/admin/content/sources").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("Nguồn tìm kiếm"));
        mockMvc.perform(get("/api/v1/admin/content/sources").param("search", "tìm").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1));
        mockMvc.perform(
                        get("/api/v1/admin/content/sources")
                                .param("search", "không có")
                                .with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    @DisplayName("Admin Content - tạo từ trùng mã trả 409 có lời giải thích, không phải 500")
    void createWordDraft_duplicateLemma_returnsConflict() throws Exception {
        var admin =
                SecurityMockMvcRequestPostProcessors.user(UserPrincipal.create(contentAdminUser));
        postJson("/api/v1/admin/content/words", wordBody("duplicated", null), admin, 201);
        postJson("/api/v1/admin/content/words", wordBody("Duplicated", null), admin, 409);
    }

    @Test
    @DisplayName("Admin Content - bộ thẻ nhận nghĩa từ đã xuất bản và ghim đúng phiên bản")
    void createDeckDraft_withPublishedSenses_buildsPinnedMembers() throws Exception {
        var admin =
                SecurityMockMvcRequestPostProcessors.user(UserPrincipal.create(contentAdminUser));
        UUID sourceId =
                UUID.fromString(
                        postJson(
                                        "/api/v1/admin/content/sources",
                                        Map.of(
                                                "title", "Nguồn thử",
                                                "sourceType", "EXTERNAL",
                                                "licenseCode", "CC-BY",
                                                "attribution", "Thử nghiệm"),
                                        admin,
                                        201)
                                .path("id")
                                .asText());
        JsonNode word =
                postJson(
                        "/api/v1/admin/content/words",
                        wordBody("pinnedword", sourceId),
                        admin,
                        201);
        String revisionId = word.path("latestRevisionId").asText();
        postJson("/api/v1/admin/content/revisions/" + revisionId + "/submit", Map.of(), admin, 200);
        postJson(
                "/api/v1/admin/content/revisions/" + revisionId + "/approve",
                Map.of("reviewNote", "ok"),
                admin,
                200);
        postJson(
                "/api/v1/admin/content/revisions/" + revisionId + "/publish", Map.of(), admin, 200);

        JsonNode snapshot =
                objectMapper.readTree(
                        revisions
                                .findById(UUID.fromString(revisionId))
                                .orElseThrow()
                                .getSnapshot());
        String senseId = snapshot.path("data").path("senses").get(0).path("id").asText();

        JsonNode deck =
                postJson(
                        "/api/v1/admin/content/decks",
                        Map.of(
                                "title",
                                "Bộ thử",
                                "level",
                                "B1",
                                "wordSenseIds",
                                List.of(senseId),
                                "sourceId",
                                sourceId),
                        admin,
                        201);
        JsonNode members =
                objectMapper
                        .readTree(
                                revisions
                                        .findById(
                                                UUID.fromString(
                                                        deck.path("latestRevisionId").asText()))
                                        .orElseThrow()
                                        .getSnapshot())
                        .path("data")
                        .path("members");
        org.junit.jupiter.api.Assertions.assertEquals(1, members.size());
        org.junit.jupiter.api.Assertions.assertEquals(
                senseId, members.get(0).path("word_sense_id").asText());
        org.junit.jupiter.api.Assertions.assertEquals(
                word.path("id").asText(), members.get(0).path("word_id").asText());
        org.junit.jupiter.api.Assertions.assertEquals(
                revisionId, members.get(0).path("pinned_word_revision_id").asText());
    }

    @Test
    @DisplayName("Admin Content - bộ thẻ từ chối nghĩa chưa xuất bản và nghĩa chọn trùng")
    void createDeckDraft_rejectsUnknownOrDuplicateSenses() throws Exception {
        var admin =
                SecurityMockMvcRequestPostProcessors.user(UserPrincipal.create(contentAdminUser));
        String unknown = UUID.randomUUID().toString();
        postJson(
                "/api/v1/admin/content/decks",
                Map.of("title", "Bộ lỗi", "wordSenseIds", List.of(unknown)),
                admin,
                400);
        postJson(
                "/api/v1/admin/content/decks",
                Map.of("title", "Bộ lỗi", "wordSenseIds", List.of(unknown, unknown)),
                admin,
                400);
    }

    @Test
    void systemAdminWithoutContentRole_canCreateDraftWithCsrf() throws Exception {
        var draft =
                new CreateWordDraftRequest(
                        "steadfast",
                        "ADJECTIVE",
                        "B2",
                        "kiên định",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        var content = objectMapper.writeValueAsString(draft);
        var principal =
                SecurityMockMvcRequestPostProcessors.user(UserPrincipal.create(systemAdminUser));

        mockMvc.perform(
                        post("/api/v1/admin/content/words")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(content)
                                .with(principal))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        post("/api/v1/admin/content/words")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(content)
                                .with(principal)
                                .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.latestRevisionId").isNotEmpty());
        mockMvc.perform(
                        get("/api/v1/admin/system/feature-flags")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(contentAdminUser))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Cập nhật cờ bị từ chối trước mutation hoặc ghi nhận audit log")
    void featureFlagUpdateRejectedBeforeMutationOrAudit() throws Exception {
        var fixture = UserPrincipal.create(systemAdminUser);
        var key = "ai_tutor_chat";
        var initialFlag = featureFlags.findById(key).orElseThrow();
        var initialEnabled = initialFlag.isEnabled();

        // Updating known key is rejected with 409 CONFLICT
        mockMvc.perform(
                        put("/api/v1/admin/system/feature-flags/{key}", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateFeatureFlagRequest(!initialEnabled)))
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(fixture)))
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "Chưa hỗ trợ điều khiển tính năng; trạng thái lưu chưa phản ánh hoạt động thực tế"));

        // DB state is not mutated
        org.assertj.core.api.Assertions.assertThat(featureFlags.findById(key))
                .hasValueSatisfying(
                        flag ->
                                org.assertj.core.api.Assertions.assertThat(flag.isEnabled())
                                        .isEqualTo(initialEnabled));

        // No audit log was recorded
        org.mockito.Mockito.verify(content, org.mockito.Mockito.never())
                .recordAuditLog(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("UPDATE_FEATURE_FLAG"),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        // Updating unknown key returns 404 NOT_FOUND
        mockMvc.perform(
                        put("/api/v1/admin/system/feature-flags/{key}", "non_existent_key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new UpdateFeatureFlagRequest(true)))
                                .with(csrf())
                                .with(SecurityMockMvcRequestPostProcessors.user(fixture)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName(
            "Admin System - SYSTEM_ADMIN có thể xem Feature Flags, Quotas và tra cứu Audit Logs")
    void system_featuresAndAudit_success() throws Exception {
        // List feature flags
        mockMvc.perform(
                        get("/api/v1/admin/system/feature-flags")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(systemAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // Update feature flag returns 409 CONFLICT
        UpdateFeatureFlagRequest flagReq = new UpdateFeatureFlagRequest(false);
        mockMvc.perform(
                        put("/api/v1/admin/system/feature-flags/ai_tutor_chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(flagReq))
                                .with(csrf())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(systemAdminUser))))
                .andExpect(status().isConflict());

        // Storage Quota
        mockMvc.perform(
                        get("/api/v1/admin/system/quotas/storage")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(systemAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucket").isNotEmpty());

        // AI Quota returns empty list
        mockMvc.perform(
                        get("/api/v1/admin/system/quotas/ai")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(systemAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());

        // Audit Logs
        mockMvc.perform(
                        get("/api/v1/admin/system/audit-logs")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(systemAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    @DisplayName(
            "Admin System - Tra cứu Audit Logs theo từng loại thực thể thực tế và gom cụm với ALL")
    void auditLogs_filterByStoredEntityTypes() throws Exception {
        var actorId = systemAdminUser.getId();
        // Fixture tạo audit records cho từng loại thực thể thực tế
        content.recordAuditLog(actorId, "CREATE_WORD", "WORD", UUID.randomUUID(), "{}", "Tạo từ");
        content.recordAuditLog(
                actorId, "CREATE_DECK", "DECK", UUID.randomUUID(), "{}", "Tạo bộ thẻ");
        content.recordAuditLog(
                actorId,
                "APPROVE_REVISION",
                "CONTENT_REVISION",
                UUID.randomUUID(),
                "{}",
                "Duyệt revision");
        content.recordAuditLog(
                actorId, "UPDATE_ROLES", "USER", actorId, "{}", "Đổi vai trò người dùng");
        content.recordAuditLog(
                actorId, "REVOKE_SESSION", "USER", actorId, "{}", "Thu hồi phiên người dùng");
        content.recordAuditLog(
                actorId, "SEED_FLAG", "FEATURE_FLAG", null, "{}", "Khởi tạo cờ hệ thống");

        var adminUser = UserPrincipal.create(systemAdminUser);

        // Lọc WORD
        mockMvc.perform(
                        get("/api/v1/admin/system/audit-logs")
                                .param("entityType", "WORD")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isNotEmpty())
                .andExpect(jsonPath("$.items[0].entityType").value("WORD"))
                .andExpect(
                        jsonPath(
                                "$.items[*].entityType",
                                org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("WORD"))));

        // Lọc DECK
        mockMvc.perform(
                        get("/api/v1/admin/system/audit-logs")
                                .param("entityType", "DECK")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isNotEmpty())
                .andExpect(jsonPath("$.items[0].entityType").value("DECK"))
                .andExpect(
                        jsonPath(
                                "$.items[*].entityType",
                                org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("DECK"))));

        // Lọc CONTENT_REVISION
        mockMvc.perform(
                        get("/api/v1/admin/system/audit-logs")
                                .param("entityType", "CONTENT_REVISION")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isNotEmpty())
                .andExpect(jsonPath("$.items[0].entityType").value("CONTENT_REVISION"))
                .andExpect(
                        jsonPath(
                                "$.items[*].entityType",
                                org.hamcrest.Matchers.everyItem(
                                        org.hamcrest.Matchers.is("CONTENT_REVISION"))));

        // Lọc USER (bao gồm cả phân quyền và phiên làm việc)
        mockMvc.perform(
                        get("/api/v1/admin/system/audit-logs")
                                .param("entityType", "USER")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isNotEmpty())
                .andExpect(jsonPath("$.items[0].entityType").value("USER"))
                .andExpect(
                        jsonPath(
                                "$.items[*].entityType",
                                org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("USER"))));

        // Lọc FEATURE_FLAG
        mockMvc.perform(
                        get("/api/v1/admin/system/audit-logs")
                                .param("entityType", "FEATURE_FLAG")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isNotEmpty())
                .andExpect(jsonPath("$.items[0].entityType").value("FEATURE_FLAG"))
                .andExpect(
                        jsonPath(
                                "$.items[*].entityType",
                                org.hamcrest.Matchers.everyItem(
                                        org.hamcrest.Matchers.is("FEATURE_FLAG"))));

        // ALL (hoặc bỏ trống entityType) trả về tất cả và phải chứa đầy đủ các loại fixture vừa tạo
        mockMvc.perform(
                        get("/api/v1/admin/system/audit-logs")
                                .param("size", "50")
                                .with(SecurityMockMvcRequestPostProcessors.user(adminUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isNotEmpty())
                .andExpect(
                        jsonPath(
                                "$.items[*].entityType",
                                org.hamcrest.Matchers.hasItems(
                                        "WORD",
                                        "DECK",
                                        "CONTENT_REVISION",
                                        "USER",
                                        "FEATURE_FLAG")))
                .andExpect(
                        jsonPath(
                                "$.items[*].action",
                                org.hamcrest.Matchers.hasItems(
                                        "CREATE_WORD",
                                        "CREATE_DECK",
                                        "APPROVE_REVISION",
                                        "UPDATE_ROLES",
                                        "REVOKE_SESSION",
                                        "SEED_FLAG")));
    }
}
