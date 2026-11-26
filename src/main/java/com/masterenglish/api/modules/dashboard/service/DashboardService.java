package com.masterenglish.api.modules.dashboard.service;

import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse;
import java.util.UUID;

public interface DashboardService {

    DashboardResponse getLearnerDashboard(UUID userId);
}
