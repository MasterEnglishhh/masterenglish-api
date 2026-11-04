package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.modules.identity.repository.PasswordChangeStore;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordChangeExpiration {

    private final PasswordChangeStore store;
    private final Clock clock;

    public PasswordChangeExpiration(PasswordChangeStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    @Transactional
    public void expireRequests() {
        Instant now = clock.instant();
        // Other password operations also lock the account before modifying request or OTP rows.
        for (UUID userId : store.lockUsersWithExpiredRequests(now, 100)) {
            store.expireOpenForUser(userId, now);
        }
    }
}
