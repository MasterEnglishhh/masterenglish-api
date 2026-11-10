package com.masterenglish.api.modules.admin.dto.user;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

public record LockUserRequest(@NotBlank String reason, Instant lockedUntil) {}
