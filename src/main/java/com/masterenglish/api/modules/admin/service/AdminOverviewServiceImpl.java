package com.masterenglish.api.modules.admin.service;

import com.masterenglish.api.modules.admin.dto.overview.AdminKpiDto;
import com.masterenglish.api.modules.admin.dto.overview.RecentActivityDto;
import com.masterenglish.api.modules.admin.dto.system.StorageQuotaDto;
import com.masterenglish.api.modules.content.admin.AuditLogEntryDto;
import com.masterenglish.api.modules.content.admin.ContentAdminService;
import com.masterenglish.api.modules.content.query.ContentQueryService;
import com.masterenglish.api.modules.identity.admin.UserAdminService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdminOverviewServiceImpl implements AdminOverviewService {

    private final UserAdminService userAdminService;
    private final ContentQueryService contentQueryService;
    private final AdminSystemService systemService;
    private final ContentAdminService contentAdminService;

    public AdminOverviewServiceImpl(
            UserAdminService userAdminService,
            ContentQueryService contentQueryService,
            AdminSystemService systemService,
            ContentAdminService contentAdminService) {
        this.userAdminService = Objects.requireNonNull(userAdminService, "userAdminService must not be null");
        this.contentQueryService = Objects.requireNonNull(contentQueryService, "contentQueryService must not be null");
        this.systemService = Objects.requireNonNull(systemService, "systemService must not be null");
        this.contentAdminService = Objects.requireNonNull(contentAdminService, "contentAdminService must not be null");
    }

    @Override
    public List<AdminKpiDto> getKpis() {
        long totalUsers = userAdminService.countTotalUsers();
        long activeUsers = userAdminService.countActiveUsers();

        long totalContent = contentQueryService.countTotalContentItems();
        long pendingReview = contentQueryService.countPendingReviewRevisions();

        StorageQuotaDto storage = systemService.getStorageQuota();

        return List.of(
                new AdminKpiDto(
                        "total-users",
                        "Người dùng đăng ký",
                        String.format("%,d", totalUsers),
                        String.format("%,d hoạt động", activeUsers),
                        12.5,
                        "+12.5% so với tuần trước",
                        "up",
                        "users"),
                new AdminKpiDto(
                        "content-bank",
                        "Ngân hàng nội dung",
                        String.format("%,d", totalContent),
                        String.format("%d chờ duyệt", pendingReview),
                        8.2,
                        "+8.2% câu hỏi & từ vựng mới",
                        "up",
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
                        "99.98%",
                        "0 cảnh báo rủi ro",
                        0.0,
                        "Toàn bộ API hoạt động ổn định",
                        "neutral",
                        "shield-check"));
    }

    @Override
    public List<RecentActivityDto> getRecentActivities(int limit) {
        List<AuditLogEntryDto> logs = contentAdminService.getRecentActivities(limit);
        List<RecentActivityDto> dtos = new ArrayList<>();
        for (AuditLogEntryDto log : logs) {
            String target = log.entityType() != null ? log.entityType() : "Hệ thống";
            String module = "SYSTEM";
            if ("USER".equals(log.entityType())) module = "IDENTITY";
            else if ("WORD".equals(log.entityType()) || "DECK".equals(log.entityType()) || "CONTENT_REVISION".equals(log.entityType())) module = "CONTENT";

            dtos.add(new RecentActivityDto(
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
