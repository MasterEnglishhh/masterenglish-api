package com.masterenglish.api.modules.identity.service;

import java.time.Instant;

public interface OtpRateLimitStore {
    int SEND_COOLDOWN_SECONDS = 60;
    int SEND_WINDOW_SECONDS = 3600;
    int VERIFY_WINDOW_SECONDS = 900;
    int SEND_EMAIL_LIMIT = 5;
    int VERIFY_EMAIL_LIMIT = 5;
    int IP_LIMIT = 300;

    long reserveSend(String emailHash, String ipHash, Instant now);

    long reserveVerification(String scope, String emailHash, String ipHash, Instant now);

    long wrongCode(String scope, String emailHash, Instant now);

    /** PostgreSQL holds the verification bucket lock until the caller commits. */
    default long checkVerificationBlocked(String scope, String emailHash, Instant now) {
        return 0;
    }
}
