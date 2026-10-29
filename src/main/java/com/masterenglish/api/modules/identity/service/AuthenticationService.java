package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.modules.identity.dto.LoginResult;
import com.masterenglish.api.modules.identity.dto.RefreshResult;
import java.util.UUID;

public interface AuthenticationService {

    LoginResult authenticate(String email, String password, String userAgent, String clientIp);

    RefreshResult refresh(String rawRefreshToken);

    void logout(UUID sessionId);
}
