package com.masterenglish.api.modules.dashboard.controller;

import com.masterenglish.api.common.security.AuthenticatedUser;
import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse;
import com.masterenglish.api.modules.dashboard.service.DashboardService;
import java.util.Objects;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Learner Dashboard summary endpoint (M02.01–M02.06). */
@RestController
@RequestMapping("/api/v1/dashboard")
@PreAuthorize("hasRole('LEARNER')")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = Objects.requireNonNull(dashboardService);
    }

    @GetMapping
    public DashboardResponse getDashboard(@CurrentUser AuthenticatedUser currentUser) {
        return dashboardService.getLearnerDashboard(currentUser.getId());
    }
}
