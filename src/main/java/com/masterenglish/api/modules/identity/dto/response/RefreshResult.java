package com.masterenglish.api.modules.identity.dto.response;

import java.time.Duration;

public record RefreshResult(
        String accessToken,
        String rawRefreshToken,
        Duration accessTokenDuration,
        Duration refreshTokenDuration) {}
