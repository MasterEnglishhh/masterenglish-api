package com.masterenglish.api.modules.admin.service;

import com.masterenglish.api.modules.admin.dto.response.AdminKpiDto;
import com.masterenglish.api.modules.admin.dto.response.RecentActivityDto;
import java.util.List;

public interface AdminOverviewService {

    List<AdminKpiDto> getKpis();

    List<RecentActivityDto> getRecentActivities(int limit);
}
