package com.masterenglish.api.modules.admin.service.impl;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.events.ContentEvents.AuditEntry;
import com.masterenglish.api.config.R2Properties;
import com.masterenglish.api.modules.admin.dto.response.AiQuotaMetricDto;
import com.masterenglish.api.modules.admin.dto.response.AuditLogEntryDto;
import com.masterenglish.api.modules.admin.dto.response.FeatureFlagDto;
import com.masterenglish.api.modules.admin.dto.response.StorageQuotaDto;
import com.masterenglish.api.modules.admin.entity.FeatureFlag;
import com.masterenglish.api.modules.admin.repository.FeatureFlagRepository;
import com.masterenglish.api.modules.admin.service.AdminSystemService;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminSystemServiceImpl implements AdminSystemService {

    private static final long DEFAULT_MAX_STORAGE_BYTES = 100L * 1024 * 1024 * 1024; // 100 GB

    private final FeatureFlagRepository flagRepository;
    private final ContentEvents content;
    private final org.springframework.beans.factory.ObjectProvider<R2Properties>
            r2PropertiesProvider;

    public AdminSystemServiceImpl(
            FeatureFlagRepository flagRepository,
            ContentEvents content,
            org.springframework.beans.factory.ObjectProvider<R2Properties> r2PropertiesProvider) {
        this.flagRepository =
                Objects.requireNonNull(flagRepository, "flagRepository must not be null");
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.r2PropertiesProvider = r2PropertiesProvider;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuditLogEntryDto> findAuditLogs(
            String search, String entityType, Pageable pageable) {
        return content.findAuditLogs(search, entityType, pageable)
                .map(AdminSystemServiceImpl::auditDto);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FeatureFlagDto> getFeatureFlags() {
        return flagRepository.findAllByOrderByCategoryAscKeyAsc().stream()
                .map(AdminSystemServiceImpl::featureFlag)
                .toList();
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public FeatureFlagDto updateFeatureFlag(String key, boolean enabled, UUID actorId) {
        FeatureFlag flag =
                flagRepository
                        .findById(key)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Feature flag not found: " + key));

        flag.updateState(enabled, actorId);
        FeatureFlag saved = flagRepository.save(flag);

        content.recordAuditLog(
                actorId,
                "UPDATE_FEATURE_FLAG",
                "FEATURE_FLAG",
                null,
                String.format("{\"key\":\"%s\",\"enabled\":%b}", key, enabled),
                "Chuyển trạng thái cờ " + flag.getName() + " sang " + (enabled ? "BẬT" : "TẮT"));

        return featureFlag(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public StorageQuotaDto getStorageQuota() {
        long usedBytes = content.getStorageReadyBytes();
        long fileCount = content.getStorageReadyCount();
        long maxBytes = DEFAULT_MAX_STORAGE_BYTES;

        double percent = maxBytes > 0 ? (double) usedBytes / maxBytes * 100.0 : 0.0;
        String usedFormatted = formatBytes(usedBytes);
        String limitFormatted = formatBytes(maxBytes);
        R2Properties r2Props = r2PropertiesProvider.getIfAvailable();
        String bucket =
                r2Props != null && r2Props.bucket() != null
                        ? r2Props.bucket()
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
    @Transactional(readOnly = true)
    public List<AiQuotaMetricDto> getAiQuotaMetrics() {
        return List.of(
                new AiQuotaMetricDto(
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

    private static FeatureFlagDto featureFlag(FeatureFlag flag) {
        return new FeatureFlagDto(
                flag.getKey(),
                flag.getName(),
                flag.getDescription(),
                flag.isEnabled(),
                flag.getCategory(),
                flag.getUpdatedAt());
    }

    private static AuditLogEntryDto auditDto(AuditEntry view) {
        return new AuditLogEntryDto(
                view.id(),
                view.actorEmail(),
                view.action(),
                view.entityType(),
                view.entityId(),
                view.details(),
                view.ipAddress(),
                view.timestamp(),
                view.severity());
    }
}
