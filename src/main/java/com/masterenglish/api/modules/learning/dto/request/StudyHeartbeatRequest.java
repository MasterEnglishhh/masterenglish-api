package com.masterenglish.api.modules.learning.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record StudyHeartbeatRequest(
        @NotNull UUID creditToken, @NotNull @Min(0) Long version, @NotNull Boolean active) {}
