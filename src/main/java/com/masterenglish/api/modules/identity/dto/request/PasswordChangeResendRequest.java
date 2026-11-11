package com.masterenglish.api.modules.identity.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record PasswordChangeResendRequest(@NotNull UUID challengeId) {}
