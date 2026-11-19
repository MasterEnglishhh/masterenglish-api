package com.masterenglish.api.modules.admin.service.impl;

import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.events.ContentEvents.AuditEntry;
import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.modules.admin.dto.response.AdminKpiDto;
import com.masterenglish.api.modules.admin.dto.response.RecentActivityDto;
import com.masterenglish.api.modules.admin.dto.response.StorageQuotaDto;
import com.masterenglish.api.modules.admin.service.AdminOverviewService;
import com.masterenglish.api.modules.admin.service.AdminSystemService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminOverviewServiceImpl implements AdminOverviewService {

    private final IdentityEvents identity;
    private final ContentEvents content;
    private final AdminSystemService systemService;

    public AdminOverviewServiceImpl(
            IdentityEvents identity, ContentEvents content, AdminSystemService systemService) {
        this.identity = Objects.requireNonNull(identity, "identity must not be null");
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.systemService =
                Objects.requireNonNull(systemService, "systemService must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminKpiDto> getKpis() {
        long totalUsers = identity.countTotalUsers();
        long activeUsers = identity.countActiveUsers();

        long totalContent = content.countTotalContentItems();
        long pendingReview = content.countPendingReviewRevisions();

        StorageQuotaDto storage = systemService.getStorageQuota();

        return List.of(
                new AdminKpiDto(
                        "total-users",
                        "Người dùng đăng ký",
                        String.format("%,d", totalUsers),
                        String.format("%,d hoạt động", activeUsers),
                        null,
                        null,
                        "neutral",
                        "users"),
                new AdminKpiDto(
                        "content-bank",
                        "Ngân hàng nội dung",
                        String.format("%,d", totalContent),
                        String.format("%d chờ duyệt", pendingReview),
                        null,
                        null,
                        "neutral",
                        "book-open"),
                new AdminKpiDto(
                        "storage-r2",
                        "Dung lượng Cloudflare R2",
                        storage.usedFormatted(),
                        "/ " + storage.limitFormatted() + " định mức",
                        storage.percentUsed(),
                        storage.percentUsed() + "% tổng bộ nhớ",
                        "neutral",
                        "database"),
                new AdminKpiDto(
                        "security-health",
                        "Trạng thái hệ thống",
                        "Chưa có dữ liệu",
                        null,
                        null,
                        null,
                        "neutral",
                        "shield-check"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecentActivityDto> getRecentActivities(int limit) {
        List<AuditEntry> logs = content.getRecentActivities(limit);
        List<RecentActivityDto> dtos = new ArrayList<>();
        for (AuditEntry log : logs) {
            String target = log.entityType() != null ? log.entityType() : "Hệ thống";
            String module = "SYSTEM";
            if ("USER".equals(log.entityType())) module = "IDENTITY";
            else if ("WORD".equals(log.entityType())
                    || "DECK".equals(log.entityType())
                    || "CONTENT_REVISION".equals(log.entityType())) module = "CONTENT";

            dtos.add(
                    new RecentActivityDto(
                            log.id().toString(),
                            log.actorEmail(),
                            "Admin",
                            log.action(),
                            target,
                            module,
                            log.timestamp(),
                            "SUCCESS"));
        }
        return dtos;
    }
}
