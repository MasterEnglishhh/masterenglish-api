package com.masterenglish.api.modules.identity.service;

import java.util.UUID;

public interface UserLockService {
    UUID resolveContentAdmin(String email);

    void verifyAndLockUser(UUID userId);

    void verifyContentAdmin(UUID userId);
}
