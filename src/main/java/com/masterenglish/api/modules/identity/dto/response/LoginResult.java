package com.masterenglish.api.modules.identity.dto.response;

import java.time.Duration;

public record LoginResult(
        UserProfileResponse user,
        String accessToken,
        String rawRefreshToken,
        Duration accessTokenDuration,
        Duration refreshTokenDuration) {}
