package com.masterenglish.api.modules.identity.dto;

import java.time.Duration;

public record RefreshResult(
        String accessToken,
        String rawRefreshToken,
        Duration accessTokenDuration,
        Duration refreshTokenDuration) {}
