package com.masterenglish.api.modules.admin.controller;

import com.masterenglish.api.modules.admin.dto.overview.AdminKpiDto;
import com.masterenglish.api.modules.admin.dto.overview.RecentActivityDto;
import com.masterenglish.api.modules.admin.service.AdminOverviewService;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/overview")
@PreAuthorize("hasRole('CONTENT_ADMIN') or hasRole('SYSTEM_ADMIN')")
public class AdminOverviewController {

    private final AdminOverviewService overviewService;

    public AdminOverviewController(AdminOverviewService overviewService) {
        this.overviewService = Objects.requireNonNull(overviewService, "overviewService must not be null");
    }

    @GetMapping("/kpis")
    public ResponseEntity<List<AdminKpiDto>> getKpis() {
        return ResponseEntity.ok(overviewService.getKpis());
    }

    @GetMapping("/activities")
    public ResponseEntity<List<RecentActivityDto>> getActivities(
            @RequestParam(required = false, defaultValue = "10") int limit) {
        return ResponseEntity.ok(overviewService.getRecentActivities(limit));
    }
}
