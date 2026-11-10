package com.masterenglish.api.modules.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.security.AuthenticatedUser;
import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.modules.admin.dto.AdminMessageResponse;
import com.masterenglish.api.modules.admin.dto.overview.AdminKpiDto;
import com.masterenglish.api.modules.admin.dto.overview.RecentActivityDto;
import com.masterenglish.api.modules.admin.dto.system.AiQuotaMetricDto;
import com.masterenglish.api.modules.admin.dto.system.FeatureFlagDto;
import com.masterenglish.api.modules.admin.dto.system.StorageQuotaDto;
import com.masterenglish.api.modules.admin.dto.system.UpdateFeatureFlagRequest;
import com.masterenglish.api.modules.admin.dto.user.LockUserRequest;
import com.masterenglish.api.modules.admin.dto.user.UpdateRolesRequest;
import com.masterenglish.api.modules.admin.service.AdminContentService;
import com.masterenglish.api.modules.admin.service.AdminOverviewService;
import com.masterenglish.api.modules.admin.service.AdminSystemService;
import com.masterenglish.api.modules.admin.service.AdminUserService;
import com.masterenglish.api.modules.content.admin.AdminContentItemDto;
import com.masterenglish.api.modules.content.admin.AdminRevisionDto;
import com.masterenglish.api.modules.content.admin.AuditLogEntryDto;
import com.masterenglish.api.modules.content.admin.ContentSourceDto;
import com.masterenglish.api.modules.content.admin.CreateContentSourceRequest;
import com.masterenglish.api.modules.content.admin.CreateDeckDraftRequest;
import com.masterenglish.api.modules.content.admin.CreateWordDraftRequest;
import com.masterenglish.api.modules.identity.admin.AdminUserDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class AdminControllerUnitTests {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private AdminOverviewService overviewServiceMock;
    private AdminUserService userServiceMock;
    private AdminContentService contentServiceMock;
    private AdminSystemService systemServiceMock;

    private MockMvc overviewMvc;
    private MockMvc userMvc;
    private MockMvc contentMvc;
    private MockMvc systemMvc;

    private final UUID testActorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        overviewServiceMock = mock(AdminOverviewService.class);
        userServiceMock = mock(AdminUserService.class);
        contentServiceMock = mock(AdminContentService.class);
        systemServiceMock = mock(AdminSystemService.class);

        HandlerMethodArgumentResolver currentUserResolver = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(com.masterenglish.api.common.security.CurrentUser.class)
                        && AuthenticatedUser.class.isAssignableFrom(parameter.getParameterType());
            }

            @Override
            public Object resolveArgument(
                    MethodParameter parameter,
                    ModelAndViewContainer mavContainer,
                    NativeWebRequest webRequest,
                    WebDataBinderFactory binderFactory) {
                return new AuthenticatedUser() {
                    @Override
                    public UUID getId() {
                        return testActorId;
                    }

                    @Override
                    public String getEmail() {
                        return "admin@test.com";
                    }
                };
            }
        };

        overviewMvc = MockMvcBuilders.standaloneSetup(new AdminOverviewController(overviewServiceMock))
                .setCustomArgumentResolvers(currentUserResolver).build();

        userMvc = MockMvcBuilders.standaloneSetup(new AdminUserController(userServiceMock))
                .setCustomArgumentResolvers(currentUserResolver).build();

        contentMvc = MockMvcBuilders.standaloneSetup(new AdminContentController(contentServiceMock))
                .setCustomArgumentResolvers(currentUserResolver).build();

        systemMvc = MockMvcBuilders.standaloneSetup(new AdminSystemController(systemServiceMock))
                .setCustomArgumentResolvers(currentUserResolver).build();
    }

    @Test
    @DisplayName("AdminOverviewController - GET /kpis và /activities")
    void testOverviewController() throws Exception {
        when(overviewServiceMock.getKpis()).thenReturn(List.of(
                new AdminKpiDto("users", "Users", "100", null, null, null, null, "users")));
        when(overviewServiceMock.getRecentActivities(5)).thenReturn(List.of(
                new RecentActivityDto("1", "admin@test.com", "Admin", "LOGIN", "System", "SYSTEM", Instant.now(), "SUCCESS")));

        overviewMvc.perform(get("/api/v1/admin/overview/kpis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Users"));

        overviewMvc.perform(get("/api/v1/admin/overview/activities?limit=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("LOGIN"));
    }

    @Test
    @DisplayName("AdminUserController - GET, PUT roles, POST lock, unlock, revoke-sessions")
    void testUserController() throws Exception {
        UUID userId = UUID.randomUUID();
        AdminUserDto mockUser = new AdminUserDto(
                userId, "u@test.com", "User", "U", "S", null,
                "ACTIVE", List.of(RoleCode.LEARNER), 1, 0, Instant.now(), null, null, null);

        when(userServiceMock.findUsers(any(), any(), any(), any())).thenReturn(
                new PageResponse<>(List.of(mockUser), 0, 10, 1, 1, false));
        when(userServiceMock.getUserDetail(userId)).thenReturn(mockUser);
        when(userServiceMock.updateUserRoles(eq(userId), any(), eq(testActorId))).thenReturn(mockUser);
        when(userServiceMock.lockUser(eq(userId), any(), eq(testActorId))).thenReturn(mockUser);
        when(userServiceMock.unlockUser(eq(userId), eq(testActorId))).thenReturn(mockUser);
        when(userServiceMock.revokeSessions(eq(userId), eq(testActorId))).thenReturn(
                new AdminMessageResponse("Sessions revoked"));

        userMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].email").value("u@test.com"));

        userMvc.perform(get("/api/v1/admin/users/" + userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("u@test.com"));

        UpdateRolesRequest roleReq = new UpdateRolesRequest(List.of(RoleCode.LEARNER, RoleCode.CONTENT_ADMIN));
        userMvc.perform(put("/api/v1/admin/users/" + userId + "/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(roleReq)))
                .andExpect(status().isOk());

        LockUserRequest lockReq = new LockUserRequest("Violation", Instant.now().plusSeconds(3600));
        userMvc.perform(post("/api/v1/admin/users/" + userId + "/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(lockReq)))
                .andExpect(status().isOk());

        userMvc.perform(post("/api/v1/admin/users/" + userId + "/unlock"))
                .andExpect(status().isOk());

        userMvc.perform(post("/api/v1/admin/users/" + userId + "/revoke-sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Sessions revoked"));
    }

    @Test
    @DisplayName("AdminContentController - Items, Words, Decks, Revisions lifecycle, Sources")
    void testContentController() throws Exception {
        UUID itemId = UUID.randomUUID();
        UUID revId = UUID.randomUUID();

        AdminContentItemDto mockItem = new AdminContentItemDto(
                itemId, "word", "WORD-word", "VOCABULARY", "WORD", "DRAFT", 1, "sum", "Admin", null, Instant.now(), "B1");
        AdminRevisionDto mockRev = new AdminRevisionDto(revId, itemId, "APPROVED", 1, null, null, Instant.now());
        ContentSourceDto mockSource = new ContentSourceDto(
                UUID.randomUUID(), "Source", "EXTERNAL", "https://s.com", "A", "CC", "ACTIVE", Instant.now());

        when(contentServiceMock.findContentItems(any(), any(), any(), any())).thenReturn(
                new PageResponse<>(List.of(mockItem), 0, 10, 1, 1, false));
        when(contentServiceMock.createWordDraft(any(), eq(testActorId))).thenReturn(mockItem);
        when(contentServiceMock.createDeckDraft(any(), eq(testActorId))).thenReturn(mockItem);
        when(contentServiceMock.submitRevision(eq(revId), eq(testActorId))).thenReturn(mockRev);
        when(contentServiceMock.approveRevision(eq(revId), any(), eq(testActorId))).thenReturn(mockRev);
        when(contentServiceMock.rejectRevision(eq(revId), any(), eq(testActorId))).thenReturn(mockRev);
        when(contentServiceMock.publishRevision(eq(revId), eq(testActorId))).thenReturn(mockRev);
        when(contentServiceMock.archiveContentItem(eq(itemId), any(), eq(testActorId))).thenReturn(
                new AdminMessageResponse("Archived"));
        when(contentServiceMock.findSources(any(), any())).thenReturn(
                new PageResponse<>(List.of(mockSource), 0, 10, 1, 1, false));
        when(contentServiceMock.createSource(any(), eq(testActorId))).thenReturn(mockSource);

        contentMvc.perform(get("/api/v1/admin/content/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("word"));

        CreateWordDraftRequest wordReq = new CreateWordDraftRequest(
                "word", "NOUN", "B1", "nghĩa", null, null, null, null, null, null);
        contentMvc.perform(post("/api/v1/admin/content/words")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wordReq)))
                .andExpect(status().isCreated());

        CreateDeckDraftRequest deckReq = new CreateDeckDraftRequest(
                "deck", "desc", "B1", List.of(), null, null);
        contentMvc.perform(post("/api/v1/admin/content/decks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deckReq)))
                .andExpect(status().isCreated());

        contentMvc.perform(post("/api/v1/admin/content/revisions/" + revId + "/submit"))
                .andExpect(status().isOk());

        contentMvc.perform(post("/api/v1/admin/content/revisions/" + revId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewNote\":\"Good\"}"))
                .andExpect(status().isOk());

        contentMvc.perform(post("/api/v1/admin/content/revisions/" + revId + "/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewNote\":\"Fix\"}"))
                .andExpect(status().isOk());

        contentMvc.perform(post("/api/v1/admin/content/revisions/" + revId + "/publish"))
                .andExpect(status().isOk());

        contentMvc.perform(post("/api/v1/admin/content/items/" + itemId + "/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Old\"}"))
                .andExpect(status().isOk());

        contentMvc.perform(get("/api/v1/admin/content/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("Source"));

        CreateContentSourceRequest srcReq = new CreateContentSourceRequest(
                "Src", "EXTERNAL", null, null, null, null, null, null);
        contentMvc.perform(post("/api/v1/admin/content/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(srcReq)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("AdminSystemController - Feature Flags, Quotas, Audit Logs")
    void testSystemController() throws Exception {
        FeatureFlagDto mockFlag = new FeatureFlagDto("flag_1", "Flag 1", "Desc", true, "AI", Instant.now());
        StorageQuotaDto mockQuota = new StorageQuotaDto("bucket", 1000L, 5000L, "1 KB", "5 KB", 20.0, 10L);
        AuditLogEntryDto mockAudit = new AuditLogEntryDto(
                UUID.randomUUID(), "a@test.com", "UPDATE", "USER", UUID.randomUUID(), "Detail", "127.0.0.1", Instant.now(), "INFO");

        when(systemServiceMock.getFeatureFlags()).thenReturn(List.of(mockFlag));
        when(systemServiceMock.updateFeatureFlag(eq("flag_1"), eq(false), eq(testActorId))).thenReturn(
                new FeatureFlagDto("flag_1", "Flag 1", "Desc", false, "AI", Instant.now()));
        when(systemServiceMock.getStorageQuota()).thenReturn(mockQuota);
        when(systemServiceMock.getAiQuotaMetrics()).thenReturn(List.of(
                new AiQuotaMetricDto("Anthropic", "claude-3-5-sonnet", 100L, 500L, 20.0, 6)));
        when(systemServiceMock.findAuditLogs(any(), any(), any())).thenReturn(
                new PageResponse<>(List.of(mockAudit), 0, 10, 1, 1, false));

        systemMvc.perform(get("/api/v1/admin/system/feature-flags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("flag_1"));

        UpdateFeatureFlagRequest updateReq = new UpdateFeatureFlagRequest(false);
        systemMvc.perform(put("/api/v1/admin/system/feature-flags/flag_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        systemMvc.perform(get("/api/v1/admin/system/quotas/storage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucket").value("bucket"));

        systemMvc.perform(get("/api/v1/admin/system/quotas/ai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].provider").value("Anthropic"));

        systemMvc.perform(get("/api/v1/admin/system/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("UPDATE"));
    }
}
