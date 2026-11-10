package com.masterenglish.api.modules.admin.service;

import com.masterenglish.api.modules.admin.dto.overview.AdminKpiDto;
import com.masterenglish.api.modules.admin.dto.overview.RecentActivityDto;
import java.util.List;

public interface AdminOverviewService {

    List<AdminKpiDto> getKpis();

    List<RecentActivityDto> getRecentActivities(int limit);
}
