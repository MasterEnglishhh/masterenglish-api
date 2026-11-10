package com.masterenglish.api.modules.admin.controller;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.pagination.PageRequestUtils;
import com.masterenglish.api.common.security.AuthenticatedUser;
import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.modules.admin.dto.system.AiQuotaMetricDto;
import com.masterenglish.api.modules.admin.dto.system.FeatureFlagDto;
import com.masterenglish.api.modules.admin.dto.system.StorageQuotaDto;
import com.masterenglish.api.modules.admin.dto.system.UpdateFeatureFlagRequest;
import com.masterenglish.api.modules.admin.service.AdminSystemService;
import com.masterenglish.api.modules.content.admin.AuditLogEntryDto;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/system")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AdminSystemController {

    private final AdminSystemService systemService;

    public AdminSystemController(AdminSystemService systemService) {
        this.systemService = Objects.requireNonNull(systemService, "systemService must not be null");
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<PageResponse<AuditLogEntryDto>> getAuditLogs(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Pageable pageable = PageRequestUtils.of(page, size);
        return ResponseEntity.ok(systemService.findAuditLogs(search, entityType, pageable));
    }

    @GetMapping("/feature-flags")
    public ResponseEntity<List<FeatureFlagDto>> getFeatureFlags() {
        return ResponseEntity.ok(systemService.getFeatureFlags());
    }

    @PutMapping("/feature-flags/{key}")
    public ResponseEntity<FeatureFlagDto> updateFeatureFlag(
            @PathVariable String key,
            @Valid @RequestBody UpdateFeatureFlagRequest request,
            @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.ok(systemService.updateFeatureFlag(key, request.enabled(), currentUser.getId()));
    }

    @GetMapping(value = {"/quotas/storage", "/meters/storage"})
    public ResponseEntity<StorageQuotaDto> getStorageQuota() {
        return ResponseEntity.ok(systemService.getStorageQuota());
    }

    @GetMapping(value = {"/quotas/ai", "/meters/ai-quota"})
    public ResponseEntity<List<AiQuotaMetricDto>> getAiQuotaMetrics() {
        return ResponseEntity.ok(systemService.getAiQuotaMetrics());
    }
}
