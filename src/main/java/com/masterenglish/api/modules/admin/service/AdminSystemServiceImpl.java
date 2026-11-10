package com.masterenglish.api.modules.admin.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.config.R2Properties;
import com.masterenglish.api.modules.admin.dto.system.AiQuotaMetricDto;
import com.masterenglish.api.modules.admin.dto.system.FeatureFlagDto;
import com.masterenglish.api.modules.admin.dto.system.StorageQuotaDto;
import com.masterenglish.api.modules.admin.entity.FeatureFlag;
import com.masterenglish.api.modules.admin.repository.FeatureFlagRepository;
import com.masterenglish.api.modules.content.admin.AuditLogEntryDto;
import com.masterenglish.api.modules.content.admin.ContentAdminService;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdminSystemServiceImpl implements AdminSystemService {

    private static final long DEFAULT_MAX_STORAGE_BYTES = 100L * 1024 * 1024 * 1024; // 100 GB

    private final FeatureFlagRepository flagRepository;
    private final ContentAdminService contentAdminService;
    private final R2Properties r2Properties;

    public AdminSystemServiceImpl(
            FeatureFlagRepository flagRepository,
            ContentAdminService contentAdminService,
            R2Properties r2Properties) {
        this.flagRepository = Objects.requireNonNull(flagRepository, "flagRepository must not be null");
        this.contentAdminService = Objects.requireNonNull(contentAdminService, "contentAdminService must not be null");
        this.r2Properties = r2Properties;
    }

    @Override
    public PageResponse<AuditLogEntryDto> findAuditLogs(
            String search, String entityType, Pageable pageable) {
        return contentAdminService.findAuditLogs(search, entityType, pageable);
    }

    @Override
    public List<FeatureFlagDto> getFeatureFlags() {
        return flagRepository.findAllByOrderByCategoryAscKeyAsc().stream()
                .map(FeatureFlagDto::from)
                .toList();
    }

    @Override
    @Transactional
    public FeatureFlagDto updateFeatureFlag(String key, boolean enabled, UUID actorId) {
        FeatureFlag flag = flagRepository.findById(key)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Feature flag not found: " + key));

        flag.updateState(enabled, actorId);
        FeatureFlag saved = flagRepository.save(flag);

        contentAdminService.recordAuditLog(
                actorId,
                "UPDATE_FEATURE_FLAG",
                "FEATURE_FLAG",
                null,
                String.format("{\"key\":\"%s\",\"enabled\":%b}", key, enabled),
                "Chuyển trạng thái cờ " + flag.getName() + " sang " + (enabled ? "BẬT" : "TẮT"));

        return FeatureFlagDto.from(saved);
    }

    @Override
    public StorageQuotaDto getStorageQuota() {
        long usedBytes = contentAdminService.getStorageReadyBytes();
        long fileCount = contentAdminService.getStorageReadyCount();
        long maxBytes = DEFAULT_MAX_STORAGE_BYTES;

        double percent = maxBytes > 0 ? (double) usedBytes / maxBytes * 100.0 : 0.0;
        String usedFormatted = formatBytes(usedBytes);
        String limitFormatted = formatBytes(maxBytes);
        String bucket = r2Properties != null && r2Properties.bucket() != null
                ? r2Properties.bucket()
                : "masterenglish-media-r2";

        return new StorageQuotaDto(
                bucket,
                usedBytes,
                maxBytes,
                usedFormatted,
                limitFormatted,
                Math.round(percent * 10.0) / 10.0,
                fileCount);
    }

    @Override
    public List<AiQuotaMetricDto> getAiQuotaMetrics() {
        return List.of(new AiQuotaMetricDto(
                "Anthropic / OpenAI",
                "claude-3-5-sonnet & gpt-4o-mini",
                1420500L,
                5000000L,
                28.4,
                6));
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char unit = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), unit);
    }
}
