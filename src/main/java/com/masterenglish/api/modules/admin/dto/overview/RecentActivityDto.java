package com.masterenglish.api.modules.admin.dto.overview;

import java.time.Instant;

public record RecentActivityDto(
        String id,
        String actorEmail,
        String actorName,
        String action,
        String target,
        String module,
        Instant timestamp,
        String status) {}
