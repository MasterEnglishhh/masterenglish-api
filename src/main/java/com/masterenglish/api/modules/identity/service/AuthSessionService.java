package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.modules.identity.dto.LoginResult;
import com.masterenglish.api.modules.identity.dto.RefreshResult;
import com.masterenglish.api.modules.identity.entity.RevokeReason;
import com.masterenglish.api.modules.identity.entity.User;
import java.util.UUID;

public interface AuthSessionService {

    LoginResult createSession(User user, String userAgent, String clientIp);

    RefreshResult rotateRefreshToken(String rawRefreshToken);

    void revokeSession(UUID sessionId, RevokeReason reason);

    boolean isSessionActive(UUID sessionId, int userAuthVersion);
}
