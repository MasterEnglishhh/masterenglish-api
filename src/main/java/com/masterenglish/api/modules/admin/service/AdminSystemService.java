package com.masterenglish.api.modules.admin.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.modules.admin.dto.system.AiQuotaMetricDto;
import com.masterenglish.api.modules.admin.dto.system.FeatureFlagDto;
import com.masterenglish.api.modules.admin.dto.system.StorageQuotaDto;
import com.masterenglish.api.modules.content.admin.AuditLogEntryDto;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface AdminSystemService {

    PageResponse<AuditLogEntryDto> findAuditLogs(
            String search, String entityType, Pageable pageable);

    List<FeatureFlagDto> getFeatureFlags();

    FeatureFlagDto updateFeatureFlag(String key, boolean enabled, UUID actorId);

    StorageQuotaDto getStorageQuota();

    List<AiQuotaMetricDto> getAiQuotaMetrics();
}
