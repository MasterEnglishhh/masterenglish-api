package com.masterenglish.api.modules.admin.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.modules.admin.dto.response.AiQuotaMetricDto;
import com.masterenglish.api.modules.admin.dto.response.AuditLogEntryDto;
import com.masterenglish.api.modules.admin.dto.response.FeatureFlagDto;
import com.masterenglish.api.modules.admin.dto.response.StorageQuotaDto;
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
