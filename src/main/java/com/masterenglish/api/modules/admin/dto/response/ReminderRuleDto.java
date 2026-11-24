package com.masterenglish.api.modules.admin.dto.response;

import java.time.Instant;
import java.util.UUID;

public record ReminderRuleDto(
        UUID id, String code, boolean enabled, int minimumGapMinutes, Instant createdAt) {}
