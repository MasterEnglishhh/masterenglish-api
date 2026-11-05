package com.masterenglish.api.modules.identity.user;

import java.util.UUID;

public interface UserLockService {
    void verifyAndLockUser(UUID userId);

    void verifyContentAdmin(UUID userId);
}
