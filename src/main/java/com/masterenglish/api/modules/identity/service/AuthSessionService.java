package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.modules.identity.dto.LoginResult;
import com.masterenglish.api.modules.identity.dto.RefreshResult;
import com.masterenglish.api.modules.identity.dto.response.AuthSessionResponse;
import com.masterenglish.api.modules.identity.entity.RevokeReason;
import com.masterenglish.api.modules.identity.entity.User;
import java.util.List;
import java.util.UUID;

public interface AuthSessionService {

    LoginResult createSession(User user, String userAgent, String clientIp);

    RefreshResult rotateRefreshToken(String rawRefreshToken);

    void revokeSession(UUID sessionId, RevokeReason reason);

    void revokeAllSessions(UUID userId, RevokeReason reason);

    List<AuthSessionResponse> listSessions(UUID userId, UUID currentSessionId);

    void revokeOwnedSession(UUID userId, UUID sessionId);

    boolean isSessionActive(UUID sessionId, UUID userId, int userAuthVersion);
}
