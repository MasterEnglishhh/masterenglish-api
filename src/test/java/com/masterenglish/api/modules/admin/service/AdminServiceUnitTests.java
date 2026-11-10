package com.masterenglish.api.modules.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.config.R2Properties;
import com.masterenglish.api.modules.admin.dto.AdminMessageResponse;
import com.masterenglish.api.modules.admin.dto.overview.AdminKpiDto;
import com.masterenglish.api.modules.admin.dto.overview.RecentActivityDto;
import com.masterenglish.api.modules.admin.dto.system.AiQuotaMetricDto;
import com.masterenglish.api.modules.admin.dto.system.FeatureFlagDto;
import com.masterenglish.api.modules.admin.dto.system.StorageQuotaDto;
import com.masterenglish.api.modules.admin.dto.user.LockUserRequest;
import com.masterenglish.api.modules.admin.dto.user.UpdateRolesRequest;
import com.masterenglish.api.modules.admin.entity.FeatureFlag;
import com.masterenglish.api.modules.admin.repository.FeatureFlagRepository;
import com.masterenglish.api.modules.content.admin.AdminContentItemDto;
import com.masterenglish.api.modules.content.admin.AdminRevisionDto;
import com.masterenglish.api.modules.content.admin.AuditLogEntryDto;
import com.masterenglish.api.modules.content.admin.ContentAdminService;
import com.masterenglish.api.modules.content.admin.CreateDeckDraftRequest;
import com.masterenglish.api.modules.content.admin.CreateWordDraftRequest;
import com.masterenglish.api.modules.content.query.ContentQueryService;
import com.masterenglish.api.modules.identity.admin.AdminUserDto;
import com.masterenglish.api.modules.identity.admin.UserAdminService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class AdminServiceUnitTests {

    @Test
    @DisplayName("AdminUserService - Khóa user và cập nhật role ghi nhận audit log")
    void adminUserService_delegatesAndAudits() {
        UserAdminService userAdminMock = mock(UserAdminService.class);
        ContentAdminService contentAdminMock = mock(ContentAdminService.class);
        AdminUserService adminUserService = new AdminUserServiceImpl(userAdminMock, contentAdminMock);

        UUID userId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        AdminUserDto mockUserDto = new AdminUserDto(
                userId, "user@test.com", "User Test", "Test", "User", null,
                "ACTIVE", List.of(RoleCode.LEARNER), 1, 1, Instant.now(), null, null, null);

        when(userAdminMock.getUserDetail(userId)).thenReturn(mockUserDto);
        when(userAdminMock.updateUserRoles(eq(userId), any(), eq(actorId))).thenReturn(mockUserDto);
        when(userAdminMock.lockUser(eq(userId), any(), any(), eq(actorId))).thenReturn(mockUserDto);

        // Get user detail
        AdminUserDto detail = adminUserService.getUserDetail(userId);
        assertThat(detail.email()).isEqualTo("user@test.com");

        // Update roles
        UpdateRolesRequest rolesReq = new UpdateRolesRequest(List.of(RoleCode.LEARNER, RoleCode.CONTENT_ADMIN));
        adminUserService.updateUserRoles(userId, rolesReq, actorId);
        verify(contentAdminMock).recordAuditLog(
                eq(actorId), eq("USER_ROLES_UPDATED"), eq("USER"), eq(userId), any(), any());

        // Lock user
        LockUserRequest lockReq = new LockUserRequest("Test lock", Instant.now().plusSeconds(3600));
        adminUserService.lockUser(userId, lockReq, actorId);
        verify(contentAdminMock).recordAuditLog(
                eq(actorId), eq("USER_LOCKED"), eq("USER"), eq(userId), any(), eq("Test lock"));

        // Revoke sessions
        AdminMessageResponse resp = adminUserService.revokeSessions(userId, actorId);
        assertThat(resp.message()).contains("thu hồi");
        verify(contentAdminMock).recordAuditLog(
                eq(actorId), eq("SESSIONS_REVOKED"), eq("USER"), eq(userId), any(), any());
    }

    @Test
    @DisplayName("AdminContentService - Chuyển giao các tác vụ nội dung sang ContentAdminService")
    void adminContentService_delegates() {
        ContentAdminService contentAdminMock = mock(ContentAdminService.class);
        AdminContentService adminContentService = new AdminContentServiceImpl(contentAdminMock);

        UUID itemId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminContentItemDto mockItem = new AdminContentItemDto(
                itemId, "test", "WORD-test", "VOCABULARY", "WORD", "DRAFT", 1, "test meaning", "Admin", null, Instant.now(), "B1");
        AdminRevisionDto mockRev = new AdminRevisionDto(
                revisionId, itemId, "IN_REVIEW", 1, null, null, Instant.now());

        when(contentAdminMock.createWordDraft(any(), eq(actorId))).thenReturn(mockItem);
        when(contentAdminMock.submitRevision(eq(revisionId), eq(actorId))).thenReturn(mockRev);

        CreateWordDraftRequest wordReq = new CreateWordDraftRequest(
                "test", "NOUN", "B1", "nghĩa", null, null, null, null, null, null);
        AdminContentItemDto created = adminContentService.createWordDraft(wordReq, actorId);
        assertThat(created.title()).isEqualTo("test");

        AdminRevisionDto submitted = adminContentService.submitRevision(revisionId, actorId);
        assertThat(submitted.status()).isEqualTo("IN_REVIEW");

        adminContentService.archiveContentItem(itemId, "archive reason", actorId);
        verify(contentAdminMock).archiveContentItem(itemId, "archive reason", actorId);
    }

    @Test
    @DisplayName("AdminSystemService - Feature Flags và Quotas")
    void adminSystemService_featureFlagsAndQuotas() {
        FeatureFlagRepository flagRepoMock = mock(FeatureFlagRepository.class);
        ContentAdminService contentAdminMock = mock(ContentAdminService.class);
        R2Properties r2PropsMock = new R2Properties(false, null, null, "test-bucket", null, null);
        AdminSystemService systemService = new AdminSystemServiceImpl(flagRepoMock, contentAdminMock, r2PropsMock);

        UUID actorId = UUID.randomUUID();
        FeatureFlag flag = new FeatureFlag("ai_tutor_chat", "AI Tutor", "AI chat", true, "AI", actorId);
        when(flagRepoMock.findById("ai_tutor_chat")).thenReturn(Optional.of(flag));
        when(flagRepoMock.save(any())).thenReturn(flag);
        when(contentAdminMock.getStorageReadyBytes()).thenReturn(10737418240L); // 10 GB
        when(contentAdminMock.getStorageReadyCount()).thenReturn(500L);

        // Update feature flag
        FeatureFlagDto updatedFlag = systemService.updateFeatureFlag("ai_tutor_chat", false, actorId);
        assertThat(updatedFlag.enabled()).isFalse();
        verify(contentAdminMock).recordAuditLog(
                eq(actorId), eq("UPDATE_FEATURE_FLAG"), eq("FEATURE_FLAG"), eq(null), any(), any());

        // Storage Quota
        StorageQuotaDto quota = systemService.getStorageQuota();
        assertThat(quota.bucket()).isEqualTo("test-bucket");
        assertThat(quota.usedFormatted()).contains("GB");
        assertThat(quota.filesCount()).isEqualTo(500L);

        // AI Quota
        List<AiQuotaMetricDto> aiMetrics = systemService.getAiQuotaMetrics();
        assertThat(aiMetrics).isNotEmpty();
    }

    @Test
    @DisplayName("AdminOverviewService - Tập hợp KPIs và dòng hoạt động")
    void adminOverviewService_kpisAndActivities() {
        UserAdminService userAdminMock = mock(UserAdminService.class);
        ContentQueryService contentQueryMock = mock(ContentQueryService.class);
        AdminSystemService systemServiceMock = mock(AdminSystemService.class);
        ContentAdminService contentAdminMock = mock(ContentAdminService.class);

        AdminOverviewService overviewService = new AdminOverviewServiceImpl(
                userAdminMock, contentQueryMock, systemServiceMock, contentAdminMock);

        when(userAdminMock.countTotalUsers()).thenReturn(1200L);
        when(userAdminMock.countActiveUsers()).thenReturn(950L);
        when(contentQueryMock.countTotalContentItems()).thenReturn(3400L);
        when(contentQueryMock.countPendingReviewRevisions()).thenReturn(15L);
        when(systemServiceMock.getStorageQuota()).thenReturn(new StorageQuotaDto(
                "bucket", 45000000L, 107374182400L, "45 MB", "100 GB", 0.04, 120L));

        when(contentAdminMock.getRecentActivities(10)).thenReturn(List.of(
                new AuditLogEntryDto(UUID.randomUUID(), "admin@test.com", "USER_LOCKED", "USER", UUID.randomUUID(), "Khóa", "127.0.0.1", Instant.now(), "WARNING")));

        List<AdminKpiDto> kpis = overviewService.getKpis();
        assertThat(kpis).hasSize(4);
        assertThat(kpis.get(0).title()).isEqualTo("Người dùng đăng ký");
        assertThat(kpis.get(0).value()).isEqualTo("1,200");
        assertThat(kpis.get(1).title()).isEqualTo("Ngân hàng nội dung");
        assertThat(kpis.get(1).value()).isEqualTo("3,400");

        List<RecentActivityDto> activities = overviewService.getRecentActivities(10);
        assertThat(activities).hasSize(1);
        assertThat(activities.get(0).action()).isEqualTo("USER_LOCKED");
    }
}
