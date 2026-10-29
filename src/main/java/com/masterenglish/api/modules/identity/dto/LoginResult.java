package com.masterenglish.api.modules.identity.dto;

import com.masterenglish.api.modules.identity.entity.User;
import java.time.Duration;

public record LoginResult(
        User user,
        String accessToken,
        String rawRefreshToken,
        Duration accessTokenDuration,
        Duration refreshTokenDuration) {}
